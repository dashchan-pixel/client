-dontobfuscate
-keep class com.mishiranu.dashchan.** { *; }
-keep class chan.** { *; }
-dontwarn kotlin.Cloneable$DefaultImpls

# Extension APKs are loaded parent-first into the client's classloader, so an extension that
# does not bundle androidx resolves it to *our* copy -- and R8 keeps only the members the
# client itself calls. Published extensions were compiled against a whole androidx: e444 1.3
# bundles Google's Flexbox, whose FlexboxLayout.onLayout() calls the three below, so opening a
# thread died with `NoSuchMethodError: No static method getLayoutDirection`. These are compat
# shims that delegate to the framework, so keeping them costs nothing on minSdk 36 and is not
# a reason for the extension to be API-36-incompatible. Keep the exact members old extensions
# reference and nothing wider; anything found later belongs here as another explicit line.
-keep class androidx.core.view.ViewCompat {
    public static int getLayoutDirection(android.view.View);
}
-keep class androidx.core.view.MarginLayoutParamsCompat {
    public static int getMarginStart(android.view.ViewGroup$MarginLayoutParams);
    public static int getMarginEnd(android.view.ViewGroup$MarginLayoutParams);
}
-keep class androidx.core.widget.CompoundButtonCompat {
    public static android.graphics.drawable.Drawable getButtonDrawable(android.widget.CompoundButton);
}
