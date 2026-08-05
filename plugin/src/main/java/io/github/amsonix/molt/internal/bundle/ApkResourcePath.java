package io.github.amsonix.molt.internal.bundle;

/** APK 内完整 res qualifier 路径的解析与重命名。 */
final class ApkResourcePath {

    private static final String RES_PREFIX = "res/";
    private static final String NINE_PATCH_SUFFIX = ".9.png";

    private final String directory;
    private final String baseName;
    private final String suffix;

    private ApkResourcePath(
            String directory,
            String baseName,
            String suffix
    ) {
        this.directory = directory;
        this.baseName = baseName;
        this.suffix = suffix;
    }

    static ApkResourcePath parse(String rawPath) {
        String path = rawPath.replace('\\', '/');
        int lastSlash = path.lastIndexOf('/');
        if (!path.startsWith(RES_PREFIX)
                || lastSlash < RES_PREFIX.length() - 1
                || lastSlash == path.length() - 1) {
            throw new IllegalArgumentException("invalid APK resource path: " + rawPath);
        }
        String directory = path.substring(0, lastSlash);
        String fileName = path.substring(lastSlash + 1);
        String baseName;
        String suffix;
        if (fileName.endsWith(NINE_PATCH_SUFFIX)) {
            baseName = fileName.substring(0, fileName.length() - NINE_PATCH_SUFFIX.length());
            suffix = NINE_PATCH_SUFFIX;
        } else {
            int extensionStart = fileName.lastIndexOf('.');
            baseName = extensionStart > 0 ? fileName.substring(0, extensionStart) : fileName;
            suffix = extensionStart > 0 ? fileName.substring(extensionStart) : "";
        }
        if (baseName.isEmpty()) {
            throw new IllegalArgumentException("resource entry name missing from path: " + rawPath);
        }
        return new ApkResourcePath(directory, baseName, suffix);
    }

    String remap(String mappedDirectory, String mappedBaseName) {
        return mappedDirectory + "/" + mappedBaseName + suffix;
    }

    String getDirectory() {
        return directory;
    }

    String getBaseName() {
        return baseName;
    }

    String getSuffix() {
        return suffix;
    }

}
