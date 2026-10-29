# EmbyVerse — reglas R8 para el APK de producción.
# Room, Media3, OkHttp, Coil y kotlinx-serialization incluyen sus propias reglas de consumidor.

# Las clases del núcleo se usan por reflexión sólo en los nombres de enum (valueOf): conservarlos.
-keepclassmembers enum com.embyverse.** { *; }

# Quitar logs de depuración en release (nunca se registran contraseñas, pero así se reduce ruido).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
