// EmbyVerse Web Local — sirve EmbyVerse Web desde tu PC y hace de intermediario con tu proveedor.
//
// Por qué existe: en internet la web está en https:// y el navegador bloquea los canales de los
// proveedores que usan http:// o que no permiten CORS. Este programa sirve la misma web por
// http:// en tu red local y descarga listas y video en nombre del navegador, así que esos
// bloqueos desaparecen. Solo atiende a equipos de tu propia red (no es un proxy público).
//
// Compilar:  ./scripts/build-local-server.sh   (genera Windows, macOS y Linux)
package main

import (
	"bufio"
	"bytes"
	"embed"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"regexp"
	"runtime"
	"strings"
	"time"
)

//go:embed web
var webFiles embed.FS

const version = "1.0.0"

var upstream = &http.Client{
	Transport: &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           (&net.Dialer{Timeout: 12 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		ResponseHeaderTimeout: 20 * time.Second,
		MaxIdleConnsPerHost:   16,
		IdleConnTimeout:       90 * time.Second,
	},
	// Sin límite total: los canales en vivo son descargas que no terminan.
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= 10 {
			return fmt.Errorf("demasiadas redirecciones")
		}
		return nil
	},
}

// Agente por defecto parecido al de un reproductor: algunos proveedores rechazan navegadores.
var userAgent = "VLC/3.0.21 LibVLC/3.0.21"

func isLocalClient(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && (ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast())
}

func cors(w http.ResponseWriter) {
	h := w.Header()
	h.Set("Access-Control-Allow-Origin", "*")
	h.Set("Access-Control-Allow-Headers", "Range")
	h.Set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Content-Type")
	h.Set("Cache-Control", "no-store")
}

var hlsTag = regexp.MustCompile(`(?m)^#EXT-X-(TARGETDURATION|STREAM-INF|MEDIA-SEQUENCE|KEY|MAP|MEDIA|I-FRAME-STREAM-INF|PLAYLIST-TYPE)`)
var uriAttr = regexp.MustCompile(`URI="([^"]+)"`)

func proxied(base *url.URL, ref string) string {
	u, err := base.Parse(strings.TrimSpace(ref))
	if err != nil {
		return ref
	}
	return "/proxy?u=" + url.QueryEscape(u.String())
}

// Reescribe una lista HLS para que segmentos, claves y variantes también pasen por este programa.
func rewriteHLS(body []byte, base *url.URL) []byte {
	var out bytes.Buffer
	sc := bufio.NewScanner(bytes.NewReader(body))
	sc.Buffer(make([]byte, 64*1024), 4*1024*1024)
	for sc.Scan() {
		line := sc.Text()
		t := strings.TrimSpace(line)
		switch {
		case t == "":
			out.WriteString(line)
		case strings.HasPrefix(t, "#"):
			out.WriteString(uriAttr.ReplaceAllStringFunc(line, func(m string) string {
				return `URI="` + proxied(base, uriAttr.FindStringSubmatch(m)[1]) + `"`
			}))
		default:
			out.WriteString(proxied(base, t))
		}
		out.WriteByte('\n')
	}
	return out.Bytes()
}

func handleProxy(w http.ResponseWriter, r *http.Request) {
	cors(w)
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if !isLocalClient(r) {
		http.Error(w, "Solo disponible en tu red local", http.StatusForbidden)
		return
	}
	target := r.URL.Query().Get("u")
	tu, err := url.Parse(target)
	if err != nil || (tu.Scheme != "http" && tu.Scheme != "https") || tu.Host == "" {
		http.Error(w, "URL no válida", http.StatusBadRequest)
		return
	}
	req, _ := http.NewRequestWithContext(r.Context(), http.MethodGet, tu.String(), nil)
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "*/*")
	// Las listas (.m3u8/.m3u) se piden completas: un trozo no se podría reescribir.
	isList := strings.HasSuffix(strings.ToLower(tu.Path), ".m3u8") || strings.HasSuffix(strings.ToLower(tu.Path), ".m3u")
	if rg := r.Header.Get("Range"); rg != "" && !isList {
		req.Header.Set("Range", rg)
	}
	resp, err := upstream.Do(req)
	if err != nil {
		if r.Context().Err() == nil {
			log.Printf("proveedor sin respuesta: %s (%v)", redact(tu), err)
			http.Error(w, "El proveedor no responde", http.StatusBadGateway)
		}
		return
	}
	defer resp.Body.Close()
	final := resp.Request.URL
	ct := resp.Header.Get("Content-Type")
	path := strings.ToLower(final.Path)
	maybeHLS := strings.Contains(strings.ToLower(ct), "mpegurl") || strings.HasSuffix(path, ".m3u8")

	if maybeHLS && (resp.StatusCode == http.StatusOK || (resp.StatusCode == http.StatusPartialContent && strings.HasPrefix(resp.Header.Get("Content-Range"), "bytes 0-"))) {
		body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
		if err != nil {
			http.Error(w, "Lista incompleta", http.StatusBadGateway)
			return
		}
		if hlsTag.Match(body) {
			body = rewriteHLS(body, final)
			ct = "application/vnd.apple.mpegurl"
		}
		w.Header().Set("Content-Type", ct)
		w.WriteHeader(http.StatusOK)
		w.Write(body)
		return
	}

	for _, k := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"} {
		if v := resp.Header.Get(k); v != "" {
			w.Header().Set(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	// Copia con vaciado frecuente para que el video en vivo llegue sin retrasos.
	buf := make([]byte, 64*1024)
	flusher, _ := w.(http.Flusher)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if rerr != nil {
			return
		}
	}
}

// Nunca se escriben usuario ni contraseña en la consola.
var credQuery = regexp.MustCompile(`(?i)((?:username|password)=)[^&]*`)
var credPath = regexp.MustCompile(`(?i)(/(?:live|movie|series)/)[^/]+/[^/]+/`)

func redact(u *url.URL) string {
	return credPath.ReplaceAllString(credQuery.ReplaceAllString(u.String(), "${1}***"), "${1}***/***/")
}

func lanIPs() []string {
	var ips []string
	addrs, _ := net.InterfaceAddrs()
	for _, a := range addrs {
		if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil && n.IP.IsPrivate() {
			ips = append(ips, n.IP.String())
		}
	}
	return ips
}

func openBrowser(u string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", u)
	case "darwin":
		cmd = exec.Command("open", u)
	default:
		cmd = exec.Command("xdg-open", u)
	}
	_ = cmd.Start()
}

func main() {
	port := flag.Int("port", 8080, "puerto")
	noOpen := flag.Bool("no-open", false, "no abrir el navegador al iniciar")
	flag.StringVar(&userAgent, "user-agent", userAgent, "User-Agent enviado al proveedor")
	flag.Parse()

	sub, _ := fs.Sub(webFiles, "web")
	files := http.FileServer(http.FS(sub))
	mux := http.NewServeMux()
	mux.HandleFunc("/proxy", handleProxy)
	mux.HandleFunc("/ev-local.js", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		fmt.Fprintf(w, "window.EV_LOCAL = { version: %q };\n", version)
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if !isLocalClient(r) {
			http.Error(w, "Solo disponible en tu red local", http.StatusForbidden)
			return
		}
		w.Header().Set("Cache-Control", "no-cache")
		files.ServeHTTP(w, r)
	})

	var ln net.Listener
	var err error
	p := *port
	for ; p < *port+20; p++ {
		if ln, err = net.Listen("tcp", fmt.Sprintf(":%d", p)); err == nil {
			break
		}
	}
	if ln == nil {
		fmt.Println("No hay ningún puerto libre entre", *port, "y", *port+19)
		os.Exit(1)
	}
	local := fmt.Sprintf("http://localhost:%d", p)
	fmt.Println("==============================================")
	fmt.Println("  EmbyVerse Web Local", version)
	fmt.Println("==============================================")
	fmt.Println("  En esta PC:       ", local)
	for _, ip := range lanIPs() {
		fmt.Printf("  En la TV / móvil:  http://%s:%d   (misma red Wi-Fi)\n", ip, p)
	}
	fmt.Println()
	fmt.Println("  Deja esta ventana abierta mientras ves contenido.")
	fmt.Println("  Si Windows pregunta por el firewall, permite el acceso en redes privadas")
	fmt.Println("  para poder usarlo desde la TV.")
	fmt.Println("==============================================")
	if !*noOpen {
		go func() { time.Sleep(600 * time.Millisecond); openBrowser(local) }()
	}
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 15 * time.Second}
	log.Fatal(srv.Serve(ln))
}
