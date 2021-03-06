/*
 * Copyright (c) 2019, 2020, Gluon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL GLUON BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.gluonhq.substrate.target;

import com.gluonhq.substrate.Constants;
import com.gluonhq.substrate.model.ClassPath;
import com.gluonhq.substrate.model.InternalProjectConfiguration;
import com.gluonhq.substrate.model.ProcessPaths;
import com.gluonhq.substrate.util.FileOps;
import com.gluonhq.substrate.util.Logger;
import com.gluonhq.substrate.util.ProcessRunner;
import com.gluonhq.substrate.util.Version;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class AndroidTargetConfiguration extends PosixTargetConfiguration {

    private final String ndk;
    private final String sdk;
    private final Path ldlld;
    private final Path clang;
    private final String hostPlatformFolder;

    private List<String> androidAdditionalSourceFiles = Arrays.asList("launcher.c", "javafx_adapter.c", "touch_events.c", "glibc_shim.c", "attach_adapter.c");
    private List<String> androidAdditionalHeaderFiles = Collections.singletonList("grandroid.h");
    private List<String> cFlags = Arrays.asList("-target", "aarch64-linux-android", "-I.");
    private List<String> linkFlags = Arrays.asList("-target", "aarch64-linux-android21", "-fPIC", "-fuse-ld=gold",
            "-Wl,--rosegment,--gc-sections,-z,noexecstack", "-shared",
            "-landroid", "-llog", "-lffi", "-llibchelper");
    private List<String> javafxLinkFlags = Arrays.asList("-Wl,--whole-archive",
            "-lprism_es2_monocle", "-lglass_monocle", "-ljavafx_font_freetype", "-ljavafx_iio", "-Wl,--no-whole-archive",
            "-lGLESv2", "-lEGL", "-lfreetype");
    private String[] capFiles = {"AArch64LibCHelperDirectives.cap",
            "AMD64LibCHelperDirectives.cap", "BuiltinDirectives.cap",
            "JNIHeaderDirectives.cap", "LibFFIHeaderDirectives.cap",
            "LLVMDirectives.cap", "PosixDirectives.cap"};
    private final String capLocation = "/native/android/cap/";
    private final List<String> iconFolders = Arrays.asList("mipmap-hdpi",
            "mipmap-ldpi", "mipmap-mdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi");
    private final List<String> sourceGlueCode = Arrays.asList("MainActivity", "KeyCode");
    private final List<String> compiledGlueCode = Arrays.asList("com/gluonhq/helloandroid/MainActivity",
            "com/gluonhq/helloandroid/MainActivity$1", "com/gluonhq/helloandroid/MainActivity$2",
            "com/gluonhq/helloandroid/MainActivity$3", "com/gluonhq/helloandroid/MainActivity$InternalSurfaceView",
            "javafx/scene/input/KeyCode", "javafx/scene/input/KeyCode$KeyCodeClass"
    );

    public AndroidTargetConfiguration( ProcessPaths paths, InternalProjectConfiguration configuration ) throws IOException {
        super(paths,configuration);

        this.sdk = fileDeps.getAndroidSDKPath().toString();
        this.ndk = fileDeps.getAndroidNDKPath().toString();
        this.hostPlatformFolder = configuration.getHostTriplet().getOs() + "-x86_64";

        Path ldguess = Paths.get(this.ndk, "toolchains", "llvm", "prebuilt", hostPlatformFolder, "bin", "ld.lld");
        this.ldlld = Files.exists(ldguess) ? ldguess : null;

        Path clangguess = Paths.get(this.ndk, "toolchains", "llvm", "prebuilt", hostPlatformFolder, "bin", "clang");
        this.clang = Files.exists(clangguess) ? clangguess : null;
    }

    /**
     * // TODO: this is 100% similar to what we do on iOS. We need something like CrossPlatformTools for this.
     * If we are not using JavaFX, we immediately return the provided classpath, no further processing needed
     * If we use JavaFX, we will first obtain the location of the JavaFX SDK for this configuration.
     * This may throw an IOException.
     * After the path to the JavaFX SDK is obtained, the JavaFX jars for the host platform are replaced by
     * the JavaFX jars for the target platform.
     * @param classPath The provided classpath
     * @return A string with the modified classpath if JavaFX is used
     * @throws IOException
     */
    @Override
    String processClassPath(String classPath) throws IOException {
        if (!projectConfiguration.isUseJavaFX()) {
            return classPath;
        }

        return new ClassPath(classPath).mapWithLibs(
                fileDeps.getJavaFXSDKLibsPath(), "javafx-graphics", "javafx-base", "javafx-controls");

    }

    @Override
    public boolean compile() throws IOException, InterruptedException {
        // we override compile as we need to do some checks first. If we have no ld.lld in android_ndk, we should not start compiling
        if (ndk == null) throw new IOException ("Can't find an Android NDK on your system. Set the environment property ANDROID_NDK");
        if (ldlld == null) throw new IOException ("You specified an android ndk, but it doesn't contain "+ndk+"/toolchains/llvm/prebuilt/"+hostPlatformFolder+"/bin/ldlld");
        if (clang == null) throw new IOException ("You specified an android ndk, but it doesn't contain "+ndk+"/toolchains/llvm/prebuilt/"+hostPlatformFolder+"/bin/clang");

        return super.compile();
    }

    @Override
    public boolean link() throws IOException, InterruptedException {
        // we override link as we need to do some checks first. If we have no clang in android_ndk, we should not start linking
        if (ndk == null) throw new IOException ("Can't find an Android NDK on your system. Set the environment property ANDROID_NDK");
        if (clang == null) throw new IOException ("You specified an android ndk, but it doesn't contain "+ndk+"/toolchains/llvm/prebuilt/"+hostPlatformFolder+"/bin/clang");
        if (sdk == null) throw new IOException ("Can't find an Android SDK on your system. Set the environment property ANDROID_SDK");

        return super.link();
    }

    @Override
    public boolean packageApp() throws IOException, InterruptedException {
        Path androidPath = prepareAndroidResources();

        ensureApkOutputDirectoriesExist();

        Path sdkPath = Paths.get(sdk);
        Path buildToolsPath = sdkPath.resolve("build-tools").resolve(findLatestBuildTool(sdkPath));
        String androidJar = sdkPath.resolve("platforms").resolve("android-27").resolve("android.jar").toString();

        String unalignedApk = getApkBinPath().resolve(projectConfiguration.getAppName() + ".unaligned.apk").toString();
        String alignedApk = getApkBinPath().resolve(projectConfiguration.getAppName() + ".apk").toString();

        if (!processPrecompiledClasses(androidJar)) {
            return false;
        }

        copyAndroidManifest(androidPath);
        copyAssets(androidPath);

        int processResult = dx(buildToolsPath);
        if (processResult != 0) {
            return false;
        }

        processResult = aapt(buildToolsPath, unalignedApk, androidJar);
        if (processResult != 0) {
            return false;
        }

        processResult = zipAlign(buildToolsPath, unalignedApk, alignedApk);
        if (processResult != 0) {
            return false;
        }

        processResult = sign(buildToolsPath, alignedApk);
        return processResult == 0;
    }

    @Override
    public boolean install() throws IOException, InterruptedException {
        Path sdkPath = Paths.get(sdk);

        Path alignedApkPath = getApkBinPath().resolve(projectConfiguration.getAppName() + ".apk");
        if (!Files.exists(alignedApkPath)) {
            throw new IOException("Application not found at path: " + alignedApkPath);
        }

        ProcessRunner install = new ProcessRunner(sdkPath.resolve("platform-tools").resolve("adb").toString(),
                "install", "-r", alignedApkPath.toString());
        int processResult = install.runProcess("install");
        if (processResult != 0) throw new IOException("Application installation failed!");

        return processResult == 0;
    }

    @Override
    public boolean runUntilEnd() throws IOException, InterruptedException {
        Path sdkPath = Paths.get(sdk);

        Runnable logcat = () -> {
            try {
                ProcessRunner clearLog = new ProcessRunner(sdkPath.resolve("platform-tools").resolve("adb").toString(),
                        "logcat", "-c");
                clearLog.runProcess("clearLog");

                ProcessRunner log = new ProcessRunner(sdkPath.resolve("platform-tools").resolve("adb").toString(),
                        "-d", "logcat", "-v", "brief", "-v", "color",
                        "GraalCompiled:V", "GraalActivity:V",
                        "GraalGluon:V", "GluonAttach:V",
                        "AndroidRuntime:E", "ActivityManager:W", "*:S");
                log.setInfo(true);
                log.runProcess("log");
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        };

        Thread logger = new Thread(logcat);
        logger.start();

        ProcessRunner run = new ProcessRunner(sdkPath.resolve("platform-tools").resolve("adb").toString(),
                "shell", "monkey", "-p", projectConfiguration.getAppId(), "1");
        int processResult = run.runProcess("run");
        if (processResult != 0) throw new IOException("Application starting failed!");

        logger.join();
        return processResult == 0;
    }

    @Override
    boolean allowHttps() {
        return false;
    }

    @Override
    List<String> getTargetSpecificAOTCompileFlags() throws IOException {
        return Arrays.asList("-H:CompilerBackend=" + Constants.BACKEND_LLVM,
                "-H:-SpawnIsolates",
                "-Dsvm.targetArch=" + projectConfiguration.getTargetTriplet().getArch(),
                "-H:+UseOnlyWritableBootImageHeap",
                "-H:+UseCAPCache",
                "-H:CAPCacheDir=" + getCapCacheDir().toAbsolutePath().toString(),
                "-H:CustomLD=" + ldlld.toAbsolutePath().toString());
    }

    @Override
    List<String> getTargetSpecificObjectFiles() throws IOException {
        return FileOps.findFile( paths.getGvmPath(), "llvm.o").map( objectFile ->
                Collections.singletonList(objectFile.toAbsolutePath().toString())
        ).orElseThrow();
    }

    @Override
    public String getCompiler() {
        return clang.toAbsolutePath().toString();
    }

    @Override
    String getLinker() {
        return clang.toAbsolutePath().toString();
    }

    @Override
    List<String> getTargetSpecificCCompileFlags() {
        return cFlags;
    }

    @Override
    List<String> getTargetSpecificLinkFlags(boolean useJavaFX, boolean usePrismSW) {
        if (!useJavaFX) return linkFlags;
        List<String> answer = new ArrayList<>();
        answer.addAll(linkFlags);
        answer.addAll(javafxLinkFlags);
        return answer;
    }

    @Override
    List<String> getTargetSpecificLinkOutputFlags() {
        return Arrays.asList("-o", getAppPath(getLinkOutputName()));
    }

    private String getLinkOutputName() {
        String appName = projectConfiguration.getAppName();
        return "lib" + appName + ".so";
    }

    @Override
    List<String> getTargetSpecificNativeLibsFlags(Path libPath, List<String> libs) {
        List<String> linkFlags = new ArrayList<>();
        linkFlags.add("-Wl,--whole-archive");
        linkFlags.addAll(libs.stream()
                .map(s -> libPath.resolve(s).toString())
                .collect(Collectors.toList()));
        linkFlags.add("-Wl,--no-whole-archive");
        return linkFlags;
    }

    @Override
    public String getAdditionalSourceFileLocation() {
        return "/native/android/c/";
    }

    @Override
    List<String> getAdditionalSourceFiles() {
        return androidAdditionalSourceFiles;
    }

    @Override
    List<String> getAdditionalHeaderFiles() {
        return androidAdditionalHeaderFiles;
    }

    @Override
    boolean useGraalVMJavaStaticLibraries() {
        return false;
    }

    private Path getApkPath() {
        return paths.getGvmPath().resolve(Constants.APK_PATH);
    }

    private Path getApkBinPath() {
        return getApkPath().resolve("bin");
    }

    private Path getApkClassPath() {
        return getApkPath().resolve("class");
    }

    private Path getApkLibPath() {
        return getApkPath().resolve("lib");
    }

    private Path getApkLibArm64Path() {
        return getApkLibPath().resolve("arm64-v8a");
    }

    private Path getApkAndroidSourcePath() {
        return getApkPath().resolve("android-source");
    }

    private void ensureApkOutputDirectoriesExist() throws IOException {
        Files.createDirectories(getApkPath());
        Files.createDirectories(getApkBinPath());
        Files.createDirectories(getApkClassPath());
        Files.createDirectories(getApkLibPath());
        Files.createDirectories(getApkLibArm64Path());
    }

    private boolean processPrecompiledClasses(String androidJar) throws IOException, InterruptedException {
        String androidCodeLocation = "/native/android/dalvik";

        if (projectConfiguration.isUsePrecompiledCode()) {
            copyPrecompiledClasses(androidCodeLocation + "/precompiled/class/");
        } else {
            return compileDalvikCode(androidCodeLocation + "/source/", androidJar) == 0;
        }

        return true;
    }

    private void copyPrecompiledClasses(String androidPrecompiled) throws IOException {
        for (String classFile : compiledGlueCode) {
            FileOps.copyResource(androidPrecompiled + classFile + ".class",
                    getApkClassPath().resolve(classFile + ".class"));
        }
    }

    private int compileDalvikCode(String androidSrc, String androidJar) throws IOException, InterruptedException {
        Files.createDirectories(getApkAndroidSourcePath());

        for (String srcFile : sourceGlueCode) {
            FileOps.copyResource(androidSrc + srcFile + ".java", getApkAndroidSourcePath().resolve(srcFile + ".java"));
        }

        List<String> sources = new ArrayList<>();

        for (String srcFile : sourceGlueCode) {
            sources.add(getApkAndroidSourcePath().resolve(srcFile + ".java").toString());
        }

        ProcessRunner processRunner = new ProcessRunner(projectConfiguration.getGraalPath().resolve("bin").resolve("javac").toString(),
                "-d", getApkClassPath().toString(),
                "-source", "1.7", "-target", "1.7",
                "-cp", getApkAndroidSourcePath().toString(),
                "-bootclasspath", androidJar);
        processRunner.addArgs(sources);
        return processRunner.runProcess("dalvikCompilation");
    }

    private void copyAndroidManifest(Path androidPath) throws IOException {
        Path androidManifest = androidPath.resolve(Constants.MANIFEST_FILE);
        if (!Files.exists(androidManifest)) {
            throw new IOException("File " + androidManifest.toString() + " not found");
        }
        Path androidManifestPath = getApkPath().resolve("AndroidManifest.xml");
        FileOps.copyFile(androidManifest, androidManifestPath);
    }

    private void copyAssets(Path androidPath) throws IOException {
        for (String iconFolder : iconFolders) {
            Path iconPath = androidPath.resolve("res").resolve(iconFolder).resolve("ic_launcher.png");
            if (!Files.exists(iconPath)) {
                throw new IOException("File " + iconPath.toString() + " not found");
            }
            Path assetPath = getApkPath().resolve("res").resolve(iconFolder);
            Files.createDirectories(assetPath);
            FileOps.copyFile(iconPath, assetPath.resolve("ic_launcher.png"));
        }
    }

    private int dx(Path buildToolsPath) throws IOException, InterruptedException {
        String dxCmd = buildToolsPath.resolve("dx").toString();

        ProcessRunner dx = new ProcessRunner(dxCmd, "--dex",
                "--output=" + getApkBinPath().resolve("classes.dex"), getApkClassPath().toString());
        return dx.runProcess("dx");
    }

    private int aapt(Path buildToolsPath, String unalignedApk, String androidJar) throws IOException, InterruptedException {
        int processResult = aaptPackage(buildToolsPath, unalignedApk, androidJar);
        if (processResult != 0) {
            return processResult;
        }

        processResult = aaptAddDxClasses(buildToolsPath, unalignedApk);
        if (processResult != 0) {
            return processResult;
        }

        return aaptAddNativeLibs(buildToolsPath, unalignedApk);
    }

    private int aaptPackage(Path buildToolsPath, String unalignedApk, String androidJar) throws IOException, InterruptedException {
        String aaptCmd = buildToolsPath.resolve("aapt").toString();
        Path androidManifestPath = getApkPath().resolve("AndroidManifest.xml");
        Path apkResPath = getApkPath().resolve("res");
        ProcessRunner aaptpackage = new ProcessRunner(aaptCmd, "package",
                "-f", "-m", "-F", unalignedApk,
                "-M", androidManifestPath.toString(),
                "-S", apkResPath.toString(),
                "-I", androidJar);
        return aaptpackage.runProcess("aaptPackage");
    }

    private int aaptAddDxClasses(Path buildToolsPath, String unalignedApk) throws IOException, InterruptedException {
        String aaptCmd = buildToolsPath.resolve("aapt").toString();
        ProcessRunner aaptAddClass = new ProcessRunner(aaptCmd, "add", unalignedApk, "classes.dex");
        return aaptAddClass.runProcess("aaptAddDxClasses", getApkBinPath().toFile());
    }

    private int aaptAddNativeLibs(Path buildToolsPath, String unalignedApk) throws IOException, InterruptedException {
        String aaptCmd = buildToolsPath.resolve("aapt").toString();

        Path libPath = paths.getAppPath().resolve(getLinkOutputName());
        Path substrateLibPath = getApkLibArm64Path().resolve("libsubstrate.so");
        Files.copy(libPath, substrateLibPath, StandardCopyOption.REPLACE_EXISTING);

        List<String> aaptAddLibsArgs = new ArrayList<>(Arrays.asList(aaptCmd, "add", unalignedApk, "lib/arm64-v8a/libsubstrate.so"));

        if (projectConfiguration.isUseJavaFX()) {
            Path javafxFreetypeLibPath = fileDeps.getJavaFXSDKLibsPath().resolve("libfreetype.so");
            Path freetypeLibPath = getApkLibArm64Path().resolve("libfreetype.so");
            Files.copy(javafxFreetypeLibPath, freetypeLibPath, StandardCopyOption.REPLACE_EXISTING);
            aaptAddLibsArgs.add("lib/arm64-v8a/libfreetype.so");
        }

        ProcessRunner aaptAddLibs = new ProcessRunner(aaptAddLibsArgs.toArray(String[]::new));
        return aaptAddLibs.runProcess("aaptAddNativeLibs", getApkPath().toFile());
    }

    private int zipAlign(Path buildToolsPath, String unalignedApk, String alignedApk) throws IOException, InterruptedException {
        String zipAlignCmd = buildToolsPath.resolve("zipalign").toString();
        ProcessRunner zipAlign = new ProcessRunner(zipAlignCmd, "-f", "4", unalignedApk, alignedApk);
        return zipAlign.runProcess("zipalign");
    }

    private int sign(Path buildToolsPath, String alignedApk) throws IOException, InterruptedException {
        createDevelopKeystore();

        String apkSignerCmd = buildToolsPath.resolve("apksigner").toString();
        ProcessRunner sign =  new ProcessRunner(apkSignerCmd, "sign", "--ks",
                Constants.USER_SUBSTRATE_PATH.resolve(Constants.ANDROID_KEYSTORE).toString(),
                "--ks-key-alias", "androiddebugkey",
                "--ks-pass", "pass:android",
                "--key-pass", "pass:android",
                alignedApk);
        return sign.runProcess("sign");
    }

    /*
     * Copies the .cap files from the jar resource and store them in
     * a directory. Return that directory
     */
    private Path getCapCacheDir() throws IOException {
        Path capPath = paths.getGvmPath().resolve("capcache");
        if (!Files.exists(capPath)) {
            Files.createDirectory(capPath);
        }
        for (String cap : capFiles) {
            FileOps.copyResource(capLocation+cap, capPath.resolve(cap));
        }
        return capPath;
    }

    private void createDevelopKeystore() throws IOException, InterruptedException {
        Path keystore = Constants.USER_SUBSTRATE_PATH.resolve(Constants.ANDROID_KEYSTORE);

        if (Files.exists(keystore)) {
            Logger.logDebug("The " + Constants.ANDROID_KEYSTORE + " file already exists, skipping");
            return;
        }

        int processResult;

        ProcessRunner generateTestKey = new ProcessRunner("keytool", "-genkey", "-v", "-keystore", keystore.toString(), "-storepass",
                "android", "-alias", "androiddebugkey", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US", "-noprompt");
        processResult = generateTestKey.runProcess("generateTestKey");
        if (processResult != 0) {
            throw new IllegalArgumentException("fatal, can not create a keystore");
        }

        Logger.logDebug("Done creating " + Constants.ANDROID_KEYSTORE);
    }

    private String findLatestBuildTool(Path sdkPath) throws IOException {
        Objects.requireNonNull(sdkPath);
        Path buildToolsPath = sdkPath.resolve("build-tools");
        if (Files.exists(buildToolsPath)) {
            return Files.walk(buildToolsPath, 1)
                    .filter(file -> Files.isDirectory(file) && !file.equals(buildToolsPath))
                    .map(file -> new Version(file.getFileName().toString()))
                    .max(Version::compareTo)
                    .map(Version::toString)
                    .orElseThrow(BuildToolNotFoundException::new);
        }
        throw new BuildToolNotFoundException();
    }

    /**
     * If android manifest and icons are present in src/android, then, this
     * path will be returned.
     *
     * Else, default android manifest and icons are copied into gvm/genSrc/android and
     * this path is returned
     *
     * @return the path where android manifest and resources are located
     * @throws IOException
     */
    private Path prepareAndroidResources() throws IOException {
        String targetOS = projectConfiguration.getTargetTriplet().getOs();
        Path targetSourcePath = paths.getSourcePath().resolve(targetOS);
        Path userManifest = targetSourcePath.resolve(Constants.MANIFEST_FILE);
        if (!Files.exists(userManifest)) {
            // copy manifest and assets to gensrc/android
            Path androidPath = paths.getGenPath().resolve(targetOS);
            Path genManifest = androidPath.resolve(Constants.MANIFEST_FILE);
            Logger.logDebug("Copy " + Constants.MANIFEST_FILE + " to " + genManifest.toString());
            FileOps.copyResource("/native/android/AndroidManifest.xml", genManifest);
            FileOps.replaceInFile(genManifest, "package='com.gluonhq.helloandroid'", "package='" + projectConfiguration.getAppId() + "'");
            FileOps.replaceInFile(genManifest, "A HelloGraal", projectConfiguration.getAppName());

            Path androidResources = androidPath.resolve("res");
            Logger.logDebug("Copy assets to " + androidResources.toString());
            for (String iconFolder : iconFolders) {
                Path assetPath = androidResources.resolve(iconFolder);
                Files.createDirectories(assetPath);
                FileOps.copyResource("/native/android/assets/res/" + iconFolder + "/ic_launcher.png", assetPath.resolve("ic_launcher.png"));
            }
            Logger.logInfo("Default Android resources generated in " + androidPath.toString() + ".\n" +
                    "Consider copying them to " + targetSourcePath.toString() + " before performing any modification");
            return androidPath;
        }
        // use manifest and assets from src/android
        return targetSourcePath;
    }

    private static class BuildToolNotFoundException extends IOException {
        public BuildToolNotFoundException() {
            super("Android build tools not found. Please install it and try again.");
        }
    }
}
