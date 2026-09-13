# Semua kelas app dipertahankan penuh — app kecil, tak ada gunanya obfuscate,
# dan ini menghindari kejutan R8 (mis. Service/Activity dari manifest, enum valueOf).
-keep class tf.fog.autoangler.** { *; }

# Jaga nama enum + valueOf/values (InjectorMode.valueOf dari SharedPreferences).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Simpan nomor baris untuk stack trace yang berguna di bot_log.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
