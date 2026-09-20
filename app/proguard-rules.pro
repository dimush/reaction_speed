# Google Mobile Ads
-keep public class com.google.android.gms.ads.** { public *; }
-keep public class com.google.ads.** { public *; }

# The Ads SDK pulls in WorkManager, which is backed by Room. Room loads the *generated*
# implementation of every database by name (`<Database>_Impl`) and instantiates it through its
# no-argument constructor, so R8 sees neither the class nor the constructor as reachable and
# removes them — the app then dies in InitializationProvider before onCreate with
# "Failed to create an instance of androidx.work.impl.WorkDatabase". Verified on the emulator:
# without these two rules the release build crashes on launch.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.room.RoomDatabase { *; }
