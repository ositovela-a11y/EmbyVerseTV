# EmbyVerse — reglas R8 para el APK de producción.
# Room, Media3, OkHttp, Coil y kotlinx-serialization incluyen sus propias reglas de consumidor.

# Las clases del núcleo se usan por reflexión sólo en los nombres de enum (valueOf): conservarlos.
-keepclassmembers enum com.embyverse.** { *; }

# Quitar logs de depuración en release (nunca se registran contraseñas, pero así se reduce ruido).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# El código propio se conserva completo (configuración remota con kotlinx-serialization, pruebas en
# emulador sobre este mismo APK). R8 sigue optimizando Compose, Media3, OkHttp, Coil y Room.
-keep class com.embyverse.** { *; }
-dontwarn org.slf4j.**
