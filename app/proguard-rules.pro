# kotlinx.serialization : garde les serializers générés des classes @Serializable de l'app
-keepclassmembers @kotlinx.serialization.Serializable class fr.niakimbo.trust.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class fr.niakimbo.trust.**$$serializer { *; }

# Receiver d'installation (référencé par le PendingIntent)
-keep class fr.niakimbo.trust.update.InstallResultReceiver
