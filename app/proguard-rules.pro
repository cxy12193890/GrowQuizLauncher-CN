-repackageclasses ''
-allowaccessmodification

# ML Kit discovers these components from manifest metadata and invokes their constructors.
-keep class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# The bundled OCR Dynamite module loads its creator by class name.
-keep class com.google.mlkit.vision.text.bundled.common.BundledTextRecognizerCreator {
    public <init>();
}
