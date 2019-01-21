-dontshrink
-keepattributes *Annotation*,InnerClasses
-keepattributes Signature,EnclosingMethod
-keepclassmembers class * implements java.io.Serializable {*;}

-dontwarn android.**
-dontwarn com.tencent.**
-dontwarn andhook.**
-dontwarn org.slf4j.**
-dontwarn org.eclipse.**

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.ContentProvider

# Parcelable
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

-keepclassmembers class * extends android.os.Binder{
    public <methods>;
}

-keepclasseswithmembernames class * {
    native <methods>;
}
# android
-keep class android.**{
    *;
}

-repackageclass z2

# Thirdparty Library
-keep class c.t.m.g.**{*;}
-keep class com.tencent.**{*;}
-keep class jonathanfinerty.once.**{public *;}

#-keepattributes SourceFile,LineNumberTable

-keep class andhook.lib.AndHook$Dalvik
-keepclassmembers class andhook.lib.AndHook$Dalvik {
   native <methods>;
}
-keep class andhook.lib.AndHook
-keepclassmembers class andhook.lib.AndHook {
   native <methods>;
}
-keep class andhook.lib.YunOSHelper
-keepclassmembers class andhook.lib.YunOSHelper {
   public *;
}

-keep class de.robv.android.xposed.*
-keepclassmembers class de.robv.android.xposed.* {
   *;
}
-keep class android.app.AndroidAppHelper
-keepclassmembers class android.app.AndroidAppHelper {
   public *;
}

-keepattributes Exceptions, InnerClasses, ...
-keep class andhook.lib.xposed.XC_MethodHook
-keepclassmembers class andhook.lib.xposed.XC_MethodHook {
   *;
}
-keep class andhook.lib.xposed.XC_MethodHook$*
-keepclassmembers class andhook.lib.xposed.XC_MethodHook$* {
   *;
}
-keep class * extends andhook.lib.xposed.XC_MethodHook
-keepclassmembers class * extends andhook.lib.xposed.XC_MethodHook {
   public *;
   protected *;
}
#-keep class * extends andhook.lib.xposed.XC_MethodReplacement
#-keepclassmembers class * extends andhook.lib.xposed.XC_MethodReplacement {
#   *;
#}

-keep class io.vposed.VPosed
-keepclassmembers class io.vposed.VPosed {
   public *;
}
# 不做预校验
#-dontpreverify

### 忽略警告
-ignorewarning

#如果引用了v4或者v7包
#-dontwarn android.support.**

#-keepattributes EnclosingMethod

 #如果有其它包有warning，在报出warning的包加入下面类似的-dontwarn 报名
#-dontwarn com.fengmap.*.**

## 注解支持
#-keepclassmembers class *{
 #  void *(android.view.View);
 # }

#保护注解
#-keepattributes *Annotation*


