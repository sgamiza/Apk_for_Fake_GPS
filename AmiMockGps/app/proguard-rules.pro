# 保留 Shizuku UserService（被框架反射拉起）
-keep class com.mockgps.shizuku.MockGpsRemoteService { *; }
-keep class com.mockgps.shizuku.IMockGpsService { *; }
-keep class com.mockgps.shizuku.IMockGpsService$* { *; }
