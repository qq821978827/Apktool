/*
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.androlib.res;

import brut.androlib.AndrolibException;
import brut.androlib.ApkOptions;
import brut.androlib.err.CantFindFrameworkResException;
import brut.androlib.meta.MetaInfo;
import brut.androlib.meta.PackageInfo;
import brut.androlib.meta.VersionInfo;
import brut.androlib.res.data.*;
import brut.androlib.res.decoder.*;
import brut.androlib.res.decoder.ARSCDecoder.ARSCData;
import brut.androlib.res.decoder.ARSCDecoder.FlagsOffset;
import brut.androlib.res.util.ExtMXSerializer;
import brut.androlib.res.util.ExtXmlSerializer;
import brut.androlib.res.xml.ResValuesXmlSerializable;
import brut.androlib.res.xml.ResXmlPatcher;
import brut.common.BrutException;
import brut.directory.*;
import brut.util.*;
import org.apache.commons.io.IOUtils;
import org.xmlpull.v1.XmlSerializer;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Android资源文件核心处理
 */
final public class AndrolibResources {
    public ResTable getResTable(ExtFile apkFile) throws AndrolibException {
        return getResTable(apkFile, true);
    }

    /**
     * 获取ResTable
     *
     * @param apkFile     apkFile 待解包
     * @param loadMainPkg 是否加载Resource.arsc
     * @return ResTable
     * @throws AndrolibException 自定义异常
     */
    public ResTable getResTable(ExtFile apkFile, boolean loadMainPkg)
        throws AndrolibException {
        ResTable resTable = new ResTable(this);
        if (loadMainPkg) {
            loadMainPkg(resTable, apkFile);
        }
        return resTable;
    }

    /**
     * 加载主要包内容  到  ResTable
     *
     * @param resTable resTable
     * @param apkFile  apk路径 待解包
     * @return ResPackage
     * @throws AndrolibException 自定义异常
     */
    public ResPackage loadMainPkg(ResTable resTable, ExtFile apkFile)
        throws AndrolibException {
        LOGGER.info("Loading resource table...");
//        package，除了开头到字符串常量池部分
        ResPackage[] pkgs = getResPackagesFromApk(apkFile, resTable, sKeepBroken);
        ResPackage pkg = null;

        switch (pkgs.length) {
            case 1:
//                获取唯一的package
                pkg = pkgs[0];
                break;
            case 2:
                if (pkgs[0].getName().equals("android")) {
//                    跳过android package
                    LOGGER.warning("Skipping \"android\" package group");
                    pkg = pkgs[1];
                    break;
                } else if (pkgs[0].getName().equals("com.htc")) {
//                    跳过htc package
                    LOGGER.warning("Skipping \"htc\" package group");
                    pkg = pkgs[1];
                    break;
                }

            default:
//                超过2个以上 选择ResSpecs最多的那个package
                pkg = selectPkgWithMostResSpecs(pkgs);
                break;
        }

        if (pkg == null) {
            throw new AndrolibException("arsc files with zero packages or no arsc file found.");
        }

        resTable.addPackage(pkg, true);
        return pkg;
    }

    /**
     * 选择最多 ResSpecs 的Package
     *
     * @param pkgs ResPackage[]
     * @return ResPackage
     */
    public ResPackage selectPkgWithMostResSpecs(ResPackage[] pkgs) {
        int id = 0;
        int value = 0;
        int index = 0;
        for (int i = 0; i < pkgs.length; i++) {
            ResPackage resPackage = pkgs[i];
            if (resPackage.getResSpecCount() > value && !resPackage.getName().equalsIgnoreCase("android")) {
                value = resPackage.getResSpecCount();
                id = resPackage.getId();
                index = i;
            }
        }

        // if id is still 0, we only have one pkgId which is "android" -> 1
        return (id == 0) ? pkgs[0] : pkgs[index];
    }

    /**
     * 加载框架文件
     *
     * @param resTable ResTable
     * @param id       id
     * @param frameTag 框架标识
     * @return ResPackage
     * @throws AndrolibException 自定义异常
     */
    public ResPackage loadFrameworkPkg(ResTable resTable, int id, String frameTag)
        throws AndrolibException {
        File apk = getFrameworkApk(id, frameTag);

        LOGGER.info("Loading resource table from file: " + apk);
        mFramework = new ExtFile(apk);
        ResPackage[] pkgs = getResPackagesFromApk(mFramework, resTable, true);

        ResPackage pkg;
        if (pkgs.length > 1) {
            pkg = selectPkgWithMostResSpecs(pkgs);
        } else if (pkgs.length == 0) {
            throw new AndrolibException("Arsc files with zero or multiple packages");
        } else {
            pkg = pkgs[0];
        }

        if (pkg.getId() != id) {
            throw new AndrolibException("Expected pkg of id: " + String.valueOf(id) + ", got: " + pkg.getId());
        }

        resTable.addPackage(pkg, false);
        return pkg;
    }

    /**
     * 解码AndroidManifest.xml 不使用Resource.arsc
     *
     * @param resTable ResTable
     * @param apkFile  ExtFile
     * @param outDir   File
     * @throws AndrolibException 自定义异常
     */
    public void decodeManifest(ResTable resTable, ExtFile apkFile, File outDir)
        throws AndrolibException {

        Duo<ResFileDecoder, AXmlResourceParser> duo = getManifestFileDecoder(false);
        ResFileDecoder fileDecoder = duo.m1;

        // Set ResAttrDecoder
        duo.m2.setAttrDecoder(new ResAttrDecoder());
        ResAttrDecoder attrDecoder = duo.m2.getAttrDecoder();

        // Fake ResPackage
        attrDecoder.setCurrentPackage(new ResPackage(resTable, 0, null));

        Directory inApk, out;
        try {
            inApk = apkFile.getDirectory();
            out = new FileDirectory(outDir);
//            仅使用框架资源来解码
            LOGGER.info("Decoding AndroidManifest.xml with only framework resources...");
            fileDecoder.decodeManifest(inApk, "AndroidManifest.xml", out, "AndroidManifest.xml");

        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    /**
     * 调整AndroidManifest
     *
     * @param resTable ResTable
     * @param filePath filePath
     * @throws AndrolibException 自定义异常
     */
    public void adjustPackageManifest(ResTable resTable, String filePath)
        throws AndrolibException {

        // compare resources.arsc package name to the one present in AndroidManifest
//        比较resources.arsc 和 现在的AndroidManifest中的包名
        ResPackage resPackage = resTable.getCurrentResPackage();
        String pkgOriginal = resPackage.getName();
        mPackageRenamed = resTable.getPackageRenamed();

        resTable.setPackageId(resPackage.getId());
        resTable.setPackageOriginal(pkgOriginal);

        // 1) Check if pkgOriginal === mPackageRenamed
        // 2) Check if pkgOriginal is ignored via IGNORED_PACKAGES
        if (pkgOriginal.equalsIgnoreCase(mPackageRenamed) || (Arrays.asList(IGNORED_PACKAGES).contains(pkgOriginal))) {
            LOGGER.info("Regular manifest package...");
        } else {
            LOGGER.info("Renamed manifest package found! Replacing " + mPackageRenamed + " with " + pkgOriginal);
//            重命名包名为pkgOriginal
            ResXmlPatcher.renameManifestPackage(new File(filePath), pkgOriginal);
        }
    }

    /**
     * 根据 resources.arsc 解码AndroidManifest.xml
     *
     * @param apkFile  apkFile
     * @param outDir   outDir
     * @param resTable resTable 解析的 resources.arsc
     * @throws AndrolibException 自定义异常
     */
    public void decodeManifestWithResources(ResTable resTable, ExtFile apkFile, File outDir)
        throws AndrolibException {

        Duo<ResFileDecoder, AXmlResourceParser> duo = getManifestFileDecoder(true);
//        Res文件解码
        ResFileDecoder fileDecoder = duo.m1;
//        Res属性解码
        ResAttrDecoder attrDecoder = duo.m2.getAttrDecoder();

//        设置属性解码的Package，为mainPackage
        attrDecoder.setCurrentPackage(resTable.listMainPackages().iterator().next());

        Directory inApk, in = null, out;
        try {
            inApk = apkFile.getDirectory();
            out = new FileDirectory(outDir);
            LOGGER.info("Decoding AndroidManifest.xml with resources...");

//            开始解码APK里面的AroidManifest.xml
            fileDecoder.decodeManifest(inApk, "AndroidManifest.xml", out, "AndroidManifest.xml");

            // Remove versionName / versionCode (aapt API 16)
            if (!resTable.getAnalysisMode()) {

//                非分析模式
                // check for a mismatch between resources.arsc package and the package listed in AndroidManifest
                // also remove the android::versionCode / versionName from manifest for rebuild
                // this is a required change to prevent aapt warning about conflicting versions
                // it will be passed as a parameter to aapt like "--min-sdk-version" via apktool.yml
//                检查资源之间是否不匹配。arsc包和AndroidManifest中列出的包
//                同时从manifest中移除android::versionCode / versionName用于重建
//                这是一个必需的更改，以防止aapt警告关于冲突的版本
//                它将通过apktool.yml作为参数传递给aapt，如“——min-sdk-version”
//                调整AndroidManifest文件
                adjustPackageManifest(resTable, outDir.getAbsolutePath() + File.separator + "AndroidManifest.xml");

//                从文件中移除“versionCode”和“versionName”等属性。
                ResXmlPatcher.removeManifestVersions(new File(
                    outDir.getAbsolutePath() + File.separator + "AndroidManifest.xml"));

//                持有packageId
                mPackageId = String.valueOf(resTable.getPackageId());
            }
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    /**
     * 解码的Res，生成解码后文件
     *
     * @param resTable ResTable
     * @param apkFile  ExtFile
     * @param outDir   File
     * @throws AndrolibException 自定义异常
     */
    public void decode(ResTable resTable, ExtFile apkFile, File outDir)
        throws AndrolibException {
        Duo<ResFileDecoder, AXmlResourceParser> duo = getResFileDecoder();
        ResFileDecoder fileDecoder = duo.m1;
        ResAttrDecoder attrDecoder = duo.m2.getAttrDecoder();

//        设置属性解码的Package，为mainPackage
        attrDecoder.setCurrentPackage(resTable.listMainPackages().iterator().next());
        Directory inApk, in = null, out;

        try {
            out = new FileDirectory(outDir);

            inApk = apkFile.getDirectory();
//            创建Res文件夹
            out = out.createDir("res");
            if (inApk.containsDir("res")) {
                in = inApk.getDir("res");
            }
            if (in == null && inApk.containsDir("r")) {
                in = inApk.getDir("r");
            }
            if (in == null && inApk.containsDir("R")) {
                in = inApk.getDir("R");
            }
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }

        ExtMXSerializer xmlSerializer = getResXmlSerializer();
//        遍历listMainPackages
        for (ResPackage pkg : resTable.listMainPackages()) {
            attrDecoder.setCurrentPackage(pkg);

            LOGGER.info("Decoding file-resources...");
            for (ResResource res : pkg.listFiles()) {
//                遍历解码Res文件
                fileDecoder.decode(res, in, out);
            }

            LOGGER.info("Decoding values */* XMLs...");
            for (ResValuesFile valuesFile : pkg.listValuesFiles()) {
//                遍历解码values */* XMLs 生成对应文件
                generateValuesFile(valuesFile, out, xmlSerializer);
            }
            generatePublicXml(pkg, out, xmlSerializer);
        }


        AndrolibException decodeError = duo.m2.getFirstError();
        if (decodeError != null) {
            //        有异常抛出异常
            throw decodeError;
        }
    }

    /**
     * 设置SDK信息
     *
     * @param map Map
     */
    public void setSdkInfo(Map<String, String> map) {
        if (map != null) {
            mMinSdkVersion = map.get("minSdkVersion");
            mTargetSdkVersion = map.get("targetSdkVersion");
            mMaxSdkVersion = map.get("maxSdkVersion");
        }
    }

    /**
     * 设置版本信息
     *
     * @param versionInfo VersionInfo
     */
    public void setVersionInfo(VersionInfo versionInfo) {
        if (versionInfo != null) {
            mVersionCode = versionInfo.versionCode;
            mVersionName = versionInfo.versionName;
        }
    }

    /**
     * 设置包名重命名
     *
     * @param packageInfo PackageInfo
     */
    public void setPackageRenamed(PackageInfo packageInfo) {
        if (packageInfo != null) {
            mPackageRenamed = packageInfo.renameManifestPackage;
        }
    }

    /**
     * 设置包ID
     *
     * @param packageInfo PackageInfo
     */
    public void setPackageId(PackageInfo packageInfo) {
        if (packageInfo != null) {
            mPackageId = packageInfo.forcedPackageId;
        }
    }

    /**
     * 设置共享库
     *
     * @param flag boolean
     */
    public void setSharedLibrary(boolean flag) {
        mSharedLibrary = flag;
    }

    /**
     * 设置稀有资源
     *
     * @param flag boolean
     */
    public void setSparseResources(boolean flag) {
        mSparseResources = flag;
    }

    /**
     * 检查目标SDK版本
     *
     * @return String
     */
    public String checkTargetSdkVersionBounds() {
        int target = mapSdkShorthandToVersion(mTargetSdkVersion);

        int min = (mMinSdkVersion != null) ? mapSdkShorthandToVersion(mMinSdkVersion) : 0;
        int max = (mMaxSdkVersion != null) ? mapSdkShorthandToVersion(mMaxSdkVersion) : target;

        target = Math.min(max, target);
        target = Math.max(min, target);
        return Integer.toString(target);
    }

    /**
     * 创建不压缩文件
     *
     * @param apkOptions ApkOptions
     * @return doNotCompressFile
     * @throws AndrolibException 自定义异常
     */
    private File createDoNotCompressExtensionsFile(ApkOptions apkOptions) throws AndrolibException {
        if (apkOptions.doNotCompress == null || apkOptions.doNotCompress.isEmpty()) {
            return null;
        }

        File doNotCompressFile;
        try {
            doNotCompressFile = File.createTempFile("APKTOOL", null);
            doNotCompressFile.deleteOnExit();

            BufferedWriter fileWriter = new BufferedWriter(new FileWriter(doNotCompressFile));
            for (String extension : apkOptions.doNotCompress) {
                fileWriter.write(extension);
                fileWriter.newLine();
            }
            fileWriter.close();

            return doNotCompressFile;
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        }
    }

    /**
     * 使用AAPT 2 打包
     *
     * @param apkFile    临时生成的文件夹
     * @param manifest   解包后的manifest文件
     * @param resDir     解包后的Res文件夹
     * @param rawDir     解包后的.9图
     * @param assetDir   解包后的AssetDir
     * @param include    包括的文件====> 使用的框架文件
     * @param cmd        命令List
     * @param customAapt build命令是否有指定AAPT
     * @throws AndrolibException 自定义异常
     */
    private void aapt2Package(File apkFile, File manifest, File resDir, File rawDir, File assetDir, File[] include,
                              List<String> cmd, boolean customAapt)
        throws AndrolibException {

//       详情@https://developer.android.google.cn/studio/command-line/aapt2?hl=zh_cn

        List<String> compileCommand = new ArrayList<>(cmd);
        File resourcesZip = null;

        if (resDir != null) {
//            创建build/resources.zip
            File buildDir = new File(resDir.getParent(), "build");
            resourcesZip = new File(buildDir, "resources.zip");
        }

        if (resDir != null && !resourcesZip.exists()) {

            // Compile the files into flat arsc files
//            将文件编译成.flat的arsc文件
//            编译命令
            cmd.add("compile");

//            使用 --dir 标记将包含多个资源文件的资源目录传递给 AAPT2，但如果这样做，您将无法获得增量资源编译的优势。
//            也就是说，如果传递整个目录，即使只有一项资源发生了改变，AAPT2 也会重新编译目录中的所有文件。
            cmd.add("--dir");
            cmd.add(resDir.getAbsolutePath());

            // Treats error that used to be valid in aapt1 as warnings in aapt2
//            将在aapt1中有效的错误视为aapt2中的警告
            cmd.add("--legacy");

            File buildDir = new File(resDir.getParent(), "build");
            resourcesZip = new File(buildDir, "resources.zip");

//            指定已编译资源的输出路径。
            cmd.add("-o");
            cmd.add(resourcesZip.getAbsolutePath());

            if (apkOptions.verbose) {
//                启用详细日志记录。
                cmd.add("-v");
            }

            if (apkOptions.noCrunch) {
//            停用 PNG 处理。
//            如果您已处理 PNG 文件，或者要创建不需要减小文件大小的调试 build，则可使用此选项。启用此选项可以加快执行速度，但会增大输出文件大小。
//                除 res/values/ 目录下的文件以外的其他所有文件都将转换为扩展名为 *.flat 的二进制 XML 文件。
//                此外，默认情况下，所有 PNG 文件都会被压缩，并采用 *.png.flat 扩展名。如果选择不压缩 PNG，您可以在编译期间使用 --no-crunch 选项
                cmd.add("--no-crunch");
            }

            try {
//                执行命令
                OS.exec(cmd.toArray(new String[0]));
                LOGGER.fine("aapt2 compile command ran: ");
                LOGGER.fine(cmd.toString());
            } catch (BrutException ex) {
                throw new AndrolibException(ex);
            }
        }

        if (manifest == null) {
            return;
        }

        // Link them into the final apk, reusing our old command after clearing for the aapt2 binary
//        将它们链接到最终的apk中，在清除aapt2二进制文件后重用我们的旧命令
        cmd = new ArrayList<>(compileCommand);
//        在链接阶段，AAPT2 会合并在编译阶段生成的所有中间文件（如资源表、二进制 XML 文件和处理过的 PNG 文件），并将它们打包成一个 APK。
//        此外，在此阶段还会生成其他辅助文件，如 R.java 和 ProGuard 规则文件。
//        不过，生成的 APK 不包含 DEX 字节码且未签名。也就是说，您无法将此 APK 部署到设备。
//        如果您不使用 Android Gradle 插件从命令行构建应用，则可以使用其他命令行工具
//        如使用 d8 将 Java 字节码编译为 DEX 字节码，以及使用 apksigner 为 APK 签名。
        cmd.add("link");

//        指定已编译资源的输出路径。
        cmd.add("-o");
        cmd.add(apkFile.getAbsolutePath());

        if (mPackageId != null && !mSharedLibrary) {
//            指定要用于应用的软件包 ID。
//            除非与 --allow-reserved-package-id 结合使用，否则您指定的软件包 ID 必须大于或等于 0x7f。
            cmd.add("--package-id");
            cmd.add(mPackageId);
        }

        if (mSharedLibrary) {
//          资源共享库
            cmd.add("--shared-lib");
        }

        if (mMinSdkVersion != null) {
//            设置要用于 AndroidManifest.xml 的默认最低 SDK 版本。
            cmd.add("--min-sdk-version");
            cmd.add(mMinSdkVersion);
        }

        if (mTargetSdkVersion != null) {
//            设置要用于 AndroidManifest.xml 的默认目标 SDK 版本。
            cmd.add("--target-sdk-version");
            cmd.add(checkTargetSdkVersionBounds());
        }

        if (mPackageRenamed != null) {
//            重命名 AndroidManifest.xml 中的软件包。
            cmd.add("--rename-manifest-package");
            cmd.add(mPackageRenamed);

//            更改插桩的目标软件包的名称。
//           它应与 --rename-manifest-package 结合使用。
            cmd.add("--rename-instrumentation-target-package");
            cmd.add(mPackageRenamed);
        }

        if (mVersionCode != null) {
//            指定没有版本代码时要注入 AndroidManifest.xml 中的版本代码（整数）
            cmd.add("--version-code");
            cmd.add(mVersionCode);
        }

        if (mVersionName != null) {
//            指定没有版本名称时要注入 AndroidManifest.xml 中的版本名称。
            cmd.add("--version-name");
            cmd.add(mVersionName);
        }

        // Disable automatic changes
//        禁用自动变化
//        停用自动样式和布局 SDK 版本控制。
        cmd.add("--no-auto-version");
//        停用矢量可绘制对象的自动版本控制。 仅当使用矢量可绘制对象库构建 APK 时，才能使用此选项。
        cmd.add("--no-version-vectors");
//        停用转换资源的自动版本控制。 仅当使用转换支持库构建 APK 时，才能使用此选项。
        cmd.add("--no-version-transitions");
//        禁止在兼容配置中自动删除具有相同值的重复资源。
        cmd.add("--no-resource-deduping");

        if (mSparseResources) {
//            允许使用二进制搜索树对稀疏条目进行编码。 这有助于优化 APK 大小，但会降低资源检索性能。
            cmd.add("--enable-sparse-encoding");
        }

        if (apkOptions.isFramework) {
            cmd.add("-x");
        }

        if (apkOptions.doNotCompress != null && !customAapt) {
            // Use custom -e option to avoid limits on commandline length.
            // Can only be used when custom aapt binary is not used.
//            使用自定义-e选项来避免对命令行长度的限制。
//            只能在不使用自定义aapt二进制文件时使用。
            String extensionsFilePath = createDoNotCompressExtensionsFile(apkOptions).getAbsolutePath();
            cmd.add("-e");
            cmd.add(extensionsFilePath);
        } else if (apkOptions.doNotCompress != null) {
//            指定您不想压缩的文件的扩展名。
            for (String file : apkOptions.doNotCompress) {
                cmd.add("-0");
                cmd.add(file);
            }
        }

        if (!apkOptions.resourcesAreCompressed) {
//            apktool.yml的compressionType
            cmd.add("-0");
            cmd.add("arsc");
        }

        if (include != null) {
            for (File file : include) {
//                提供平台的 android.jar 或其他 APK（如 framework-res.apk）的路径，这在构建功能时可能很有用。
//                如果您要在资源文件中使用带有 android 命名空间（例如 android:id）的属性，则必须使用此标记。
                cmd.add("-I");
                cmd.add(file.getPath());
            }
        }

//        指定要构建的 Android 清单文件的路径。
//        这是一个必需的标记，因为清单文件中包含有关您应用的基本信息（如软件包名称和应用 ID）。
        cmd.add("--manifest");
        cmd.add(manifest.getAbsolutePath());

        if (assetDir != null) {
//            指定要包含在 APK 中的资产目录。
//            您可以使用此目录存储未处理的原始文件。如需了解详情，请参阅@https://developer.android.google.cn/guide/topics/resources/providing-resources?hl=zh_cn#OriginalFiles。
            cmd.add("-A");
            cmd.add(assetDir.getAbsolutePath());
        }

        if (rawDir != null) {
//            传递要链接的单个 .flat 文件，使用 overlay 语义，而不使用 <add-resource> 标记。
//            如果您提供与现有文件重叠（扩展或修改现有文件）的资源文件，系统会使用最后提供的冲突资源。
            cmd.add("-R");
            cmd.add(rawDir.getAbsolutePath());
        }

        if (apkOptions.verbose) {
//            启用详细日志记录。
            cmd.add("-v");
        }

        if (resourcesZip != null) {
            cmd.add(resourcesZip.getAbsolutePath());
        }

        try {
//            执行命令
            OS.exec(cmd.toArray(new String[0]));
            LOGGER.fine("aapt2 link command ran: ");
            LOGGER.fine(cmd.toString());
        } catch (BrutException ex) {
            throw new AndrolibException(ex);
        }
    }

    /**
     * 区分AAPT2  所以这里搞了个AAPT1
     *
     * @param apkFile    临时生成的文件夹
     * @param manifest   解包后的manifest文件
     * @param resDir     解包后的Res文件夹
     * @param rawDir     解包后的.9图
     * @param assetDir   解包后的AssetDir
     * @param include    包括的文件====> 使用的框架文件
     * @param cmd        命令List
     * @param customAapt build命令是否有指定AAPT
     * @throws AndrolibException 自定义异常
     */
    private void aapt1Package(File apkFile, File manifest, File resDir, File rawDir, File assetDir, File[] include,
                              List<String> cmd, boolean customAapt)
        throws AndrolibException {

        cmd.add("p");

        if (apkOptions.verbose) { // output aapt verbose
//            输出aapt详细
            cmd.add("-v");
        }
        if (apkOptions.updateFiles) {
            cmd.add("-u");
        }
        if (apkOptions.debugMode) { // inject debuggable="true" into manifest
//           将debuggable="true"注入manifest
            cmd.add("--debug-mode");
        }
        if (apkOptions.noCrunch) {
//            在构建步骤中禁用资源文件的处理。
//            停用 PNG 处理。
//            如果您已处理 PNG 文件，或者要创建不需要减小文件大小的调试 build，则可使用此选项。启用此选项可以加快执行速度，但会增大输出文件大小。
            cmd.add("--no-crunch");
        }
        // force package id so that some frameworks build with correct id
        // disable if user adds own aapt (can't know if they have this feature)
        if (mPackageId != null && !customAapt && !mSharedLibrary) {
//            强制包id，以便一些框架使用正确的id构建
//            如果用户添加了自己的aapt则禁用(不知道他们是否有这个功能)
            cmd.add("--forced-package-id");
            cmd.add(mPackageId);
        }
        if (mSharedLibrary) {
//            是否共享库
            cmd.add("--shared-lib");
        }
        if (mMinSdkVersion != null) {
//            最小SDK版本
            cmd.add("--min-sdk-version");
            cmd.add(mMinSdkVersion);
        }
        if (mTargetSdkVersion != null) {
//            目标SDK版本
            cmd.add("--target-sdk-version");

            // Ensure that targetSdkVersion is between minSdkVersion/maxSdkVersion if
            // they are specified.
            cmd.add(checkTargetSdkVersionBounds());
        }
        if (mMaxSdkVersion != null) {
//            最大SDK版本
            cmd.add("--max-sdk-version");
            cmd.add(mMaxSdkVersion);

            // if we have max sdk version, set --max-res-version
            // so we can ignore anything over that during build.
            cmd.add("--max-res-version");
            cmd.add(mMaxSdkVersion);
        }
        if (mPackageRenamed != null) {
//            重命名manifest-package
            cmd.add("--rename-manifest-package");
            cmd.add(mPackageRenamed);
        }
        if (mVersionCode != null) {
//            版本code
            cmd.add("--version-code");
            cmd.add(mVersionCode);
        }
        if (mVersionName != null) {
//            版本name
            cmd.add("--version-name");
            cmd.add(mVersionName);
        }
//        停用矢量可绘制对象的自动版本控制。 仅当使用矢量可绘制对象库构建 APK 时，才能使用此选项。
        cmd.add("--no-version-vectors");
//        如果编译出来的文件已经存在，强制覆盖。
        cmd.add("-F");
        cmd.add(apkFile.getAbsolutePath());

        if (apkOptions.isFramework) {
            cmd.add("-x");
        }

        if (apkOptions.doNotCompress != null && !customAapt) {
            // Use custom -e option to avoid limits on commandline length.
            // Can only be used when custom aapt binary is not used.
//            使用自定义-e选项来避免对命令行长度的限制。
//            只能在不使用自定义aapt二进制文件时使用。
            String extensionsFilePath = createDoNotCompressExtensionsFile(apkOptions).getAbsolutePath();
            cmd.add("-e");
            cmd.add(extensionsFilePath);
        } else if (apkOptions.doNotCompress != null) {
            for (String file : apkOptions.doNotCompress) {
//                不压缩
                cmd.add("-0");
                cmd.add(file);
            }
        }

        if (!apkOptions.resourcesAreCompressed) {
//            不压缩
            cmd.add("-0");
            cmd.add("arsc");
        }

        if (include != null) {
            for (File file : include) {
//                某个版本平台的android.jar的路径
                cmd.add("-I");
                cmd.add(file.getPath());
            }
        }
        if (resDir != null) {
//            res文件夹路径
            cmd.add("-S");
            cmd.add(resDir.getAbsolutePath());
        }
        if (manifest != null) {
//            AndroidManifest.xml的路径
            cmd.add("-M");
            cmd.add(manifest.getAbsolutePath());
        }
        if (assetDir != null) {
//            assert文件夹的路径
            cmd.add("-A");
            cmd.add(assetDir.getAbsolutePath());
        }
        if (rawDir != null) {
            cmd.add(rawDir.getAbsolutePath());
        }
        try {
//            执行命令
            OS.exec(cmd.toArray(new String[0]));
            LOGGER.fine("command ran: ");
            LOGGER.fine(cmd.toString());
        } catch (BrutException ex) {
            throw new AndrolibException(ex);
        }
    }

    /**
     * 使用AAPT进行打包
     *
     * @param apkFile  临时生成的文件夹
     * @param manifest 解包后的manifest文件
     * @param resDir   解包后的Res文件夹
     * @param rawDir   解包后的.9图
     * @param assetDir 解包后的AssetDir
     * @param include  包括的文件====> 使用的框架文件
     * @throws AndrolibException 自定义异常
     */
    public void aaptPackage(File apkFile, File manifest, File resDir, File rawDir, File assetDir, File[] include)
        throws AndrolibException {

//        AAPT的路径
        String aaptPath = apkOptions.aaptPath;
        boolean customAapt = !aaptPath.isEmpty();
        List<String> cmd = new ArrayList<String>();

        try {
//            获取AAPT路径
            String aaptCommand = AaptManager.getAaptExecutionCommand(aaptPath, getAaptBinaryFile());
            cmd.add(aaptCommand);
        } catch (BrutException ex) {
            LOGGER.warning("aapt: " + ex.getMessage() + " (defaulting to $PATH binary)");
            cmd.add(AaptManager.getAaptBinaryName(getAaptVersion()));
        }

        if (apkOptions.isAapt2()) {
            aapt2Package(apkFile, manifest, resDir, rawDir, assetDir, include, cmd, customAapt);
            return;
        }
        aapt1Package(apkFile, manifest, resDir, rawDir, assetDir, include, cmd, customAapt);
    }

    /**
     * 压缩成ZIP文件
     *
     * @param apkFile apkFile
     * @param rawDir rawDir
     * @param assetDir assetDir
     * @throws AndrolibException 自定义异常
     */
    public void zipPackage(File apkFile, File rawDir, File assetDir)
        throws AndrolibException {

        try {
            ZipUtils.zipFolders(rawDir, apkFile, assetDir, apkOptions.doNotCompress);
        } catch (IOException | BrutException ex) {
            throw new AndrolibException(ex);
        }
    }

    public int getMinSdkVersionFromAndroidCodename(MetaInfo meta, String sdkVersion) {
        int sdkNumber = mapSdkShorthandToVersion(sdkVersion);

        if (sdkNumber == ResConfigFlags.SDK_BASE) {
            return Integer.parseInt(meta.sdkInfo.get("minSdkVersion"));
        }
        return sdkNumber;
    }

    private int mapSdkShorthandToVersion(String sdkVersion) {
        switch (sdkVersion.toUpperCase()) {
            case "M":
                return ResConfigFlags.SDK_MNC;
            case "N":
                return ResConfigFlags.SDK_NOUGAT;
            case "O":
                return ResConfigFlags.SDK_OREO;
            case "P":
                return ResConfigFlags.SDK_P;
            case "Q":
                return ResConfigFlags.SDK_Q;
            case "R":
                return ResConfigFlags.SDK_R;
            case "S":
                return ResConfigFlags.SDK_S;
            case "T":
            case "Tiramisu":
                return ResConfigFlags.SDK_DEVELOPMENT;
            default:
                return Integer.parseInt(sdkVersion);
        }
    }

    public boolean detectWhetherAppIsFramework(File appDir)
        throws AndrolibException {
        File publicXml = new File(appDir, "res/values/public.xml");
        if (!publicXml.exists()) {
            return false;
        }

        Iterator<String> it;
        try {
            it = IOUtils.lineIterator(new FileReader(new File(appDir,
                "res/values/public.xml")));
        } catch (FileNotFoundException ex) {
            throw new AndrolibException(
                "Could not detect whether app is framework one", ex);
        }
        it.next();
        it.next();
        return it.next().contains("0x01");
    }

    public Duo<ResFileDecoder, AXmlResourceParser> getResFileDecoder() {
        ResStreamDecoderContainer decoders = new ResStreamDecoderContainer();
        decoders.setDecoder("raw", new ResRawStreamDecoder());
        decoders.setDecoder("9patch", new Res9patchStreamDecoder());

        AXmlResourceParser axmlParser = new AXmlResourceParser();
        axmlParser.setAttrDecoder(new ResAttrDecoder());
        decoders.setDecoder("xml", new XmlPullStreamDecoder(axmlParser, getResXmlSerializer()));

        return new Duo<ResFileDecoder, AXmlResourceParser>(new ResFileDecoder(decoders), axmlParser);
    }

    /**
     * 解码ManifestFile
     *
     * @param withResources boolean
     * @return Duo<ResFileDecoder, AXmlResourceParser>.
     */
    public Duo<ResFileDecoder, AXmlResourceParser> getManifestFileDecoder(boolean withResources) {
//        解码容器
        ResStreamDecoderContainer decoders = new ResStreamDecoderContainer();

//        解析Xml
        AXmlResourceParser axmlParser = new AndroidManifestResourceParser();
        if (withResources) {
//            设置属性解码
            axmlParser.setAttrDecoder(new ResAttrDecoder());
        }
//        解码容器设置解码类型用到的流
        decoders.setDecoder("xml", new XmlPullStreamDecoder(axmlParser, getResXmlSerializer()));

        return new Duo<ResFileDecoder, AXmlResourceParser>(new ResFileDecoder(decoders), axmlParser);
    }

    /**
     * 获取ResXmlSerializer
     *
     * @return ExtMXSerializer
     */
    public ExtMXSerializer getResXmlSerializer() {
        ExtMXSerializer serial = new ExtMXSerializer();
//      设置缩进
        serial.setProperty(ExtXmlSerializer.PROPERTY_SERIALIZER_INDENTATION, "    ");
//      属性序列化行分隔符
        serial.setProperty(ExtXmlSerializer.PROPERTY_SERIALIZER_LINE_SEPARATOR, System.getProperty("line.separator"));
//      编码格式
        serial.setProperty(ExtXmlSerializer.PROPERTY_DEFAULT_ENCODING, "utf-8");
//      DisabledAttrEscape
        serial.setDisabledAttrEscape(true);
        return serial;
    }

    /**
     * 生成value文件
     *
     * @param valuesFile ResValuesFile
     * @param out        输出
     * @param serial     ExtXmlSerializer 序列化
     * @throws AndrolibException 自定义异常
     */
    private void generateValuesFile(ResValuesFile valuesFile, Directory out,
                                    ExtXmlSerializer serial) throws AndrolibException {
        try {
            OutputStream outStream = out.getFileOutput(valuesFile.getPath());
            serial.setOutput((outStream), null);
            serial.startDocument(null, null);
//            开始的第一个头标签
            serial.startTag(null, "resources");

            for (ResResource res : valuesFile.listResources()) {
                if (valuesFile.isSynthesized(res)) {
//                  跳过
                    continue;
                }
//               写入资源
                ((ResValuesXmlSerializable) res.getValue()).serializeToResValuesXml(serial, res);
            }

//            标签结尾
            serial.endTag(null, "resources");
            serial.newLine();
            serial.endDocument();
            serial.flush();
            outStream.close();
        } catch (IOException | DirectoryException ex) {
            throw new AndrolibException("Could not generate: " + valuesFile.getPath(), ex);
        }
    }

    /**
     * 生成public.xml文件
     *
     * @param pkg    ResPackage
     * @param out    Directory
     * @param serial XmlSerializer
     * @throws AndrolibException 自定义异常
     */
    private void generatePublicXml(ResPackage pkg, Directory out,
                                   XmlSerializer serial) throws AndrolibException {
        try {
            OutputStream outStream = out.getFileOutput("values/public.xml");
            serial.setOutput(outStream, null);
            serial.startDocument(null, null);
            serial.startTag(null, "resources");

            for (ResResSpec spec : pkg.listResSpecs()) {
                serial.startTag(null, "public");
                serial.attribute(null, "type", spec.getType().getName());
                serial.attribute(null, "name", spec.getName());
                serial.attribute(null, "id", String.format("0x%08x", spec.getId().id));
                serial.endTag(null, "public");
            }

            serial.endTag(null, "resources");
            serial.endDocument();
            serial.flush();
            outStream.close();
        } catch (IOException | DirectoryException ex) {
            throw new AndrolibException("Could not generate public.xml file", ex);
        }
    }

    /**
     * 获取resource.arsc 里面的package部分
     *
     * @param apkFile    apkFile
     * @param resTable   resTable
     * @param keepBroken keepBroken 遇到不能识的，是否跳过
     * @return ResPackage[]
     * @throws AndrolibException 自定义异常
     */
    private ResPackage[] getResPackagesFromApk(ExtFile apkFile, ResTable resTable, boolean keepBroken)
        throws AndrolibException {
        try {
            Directory dir = apkFile.getDirectory();
            BufferedInputStream bfi = new BufferedInputStream(dir.getFileInput("resources.arsc"));
            try {
                return ARSCDecoder.decode(bfi, false, keepBroken, resTable).getPackages();
            } finally {
                try {
                    bfi.close();
                } catch (IOException ignored) {
                }
            }
        } catch (DirectoryException ex) {
            throw new AndrolibException("Could not load resources.arsc from file: " + apkFile, ex);
        }
    }

    /**
     * 获取框架文件
     *
     * @param id       id
     * @param frameTag 框架标识
     * @return File
     * @throws AndrolibException 自定义异常
     */
    public File getFrameworkApk(int id, String frameTag)
        throws AndrolibException {
        File dir = getFrameworkDir();
        File apk;

        if (frameTag != null) {
            apk = new File(dir, String.valueOf(id) + '-' + frameTag + ".apk");
            if (apk.exists()) {
                return apk;
            }
        }

        apk = new File(dir, String.valueOf(id) + ".apk");
        if (apk.exists()) {
            return apk;
        }

        if (id == 1) {
//            拷贝框架文件
            try (InputStream in = getAndroidFrameworkResourcesAsStream();
                 OutputStream out = new FileOutputStream(apk)) {
                IOUtils.copy(in, out);
                return apk;
            } catch (IOException ex) {
                throw new AndrolibException(ex);
            }
        }

        throw new CantFindFrameworkResException(id);
    }

    /**
     * 清空框架文件
     *
     * @throws AndrolibException 自定义异常
     */
    public void emptyFrameworkDirectory() throws AndrolibException {
        File dir = getFrameworkDir();
        File apk;

        apk = new File(dir, "1.apk");

        if (!apk.exists()) {
            LOGGER.warning("Can't empty framework directory, no file found at: " + apk.getAbsolutePath());
        } else {
            try {
                if (apk.exists() && dir.listFiles().length > 1 && !apkOptions.forceDeleteFramework) {
                    LOGGER.warning("More than default framework detected. Please run command with `--force` parameter to wipe framework directory.");
                } else {
                    for (File file : dir.listFiles()) {
                        if (file.isFile() && file.getName().endsWith(".apk")) {
                            LOGGER.info("Removing " + file.getName() + " framework file...");
                            file.delete();
                        }
                    }
                }
            } catch (NullPointerException e) {
                throw new AndrolibException(e);
            }
        }
    }

    /**
     * 打印框架list
     *
     * @throws AndrolibException 自定义异常
     */
    public void listFrameworkDirectory() throws AndrolibException {
        File dir = getFrameworkDir();
        if (dir == null) {
            LOGGER.severe("No framework directory found. Nothing to list.");
            return;
        }

        for (File file : Objects.requireNonNull(dir.listFiles())) {
            if (file.isFile() && file.getName().endsWith(".apk")) {
                LOGGER.info(file.getName());
            }
        }
    }

    /**
     * 安装框架文件
     *
     * @param frameFile File
     * @throws AndrolibException 自定义异常
     */
    public void installFramework(File frameFile) throws AndrolibException {
        installFramework(frameFile, apkOptions.frameworkTag);
    }

    /**
     * 安装框架文件
     *
     * @param frameFile File
     * @param tag       标识
     * @throws AndrolibException 自定义异常
     */
    public void installFramework(File frameFile, String tag)
        throws AndrolibException {
        InputStream in = null;
        ZipOutputStream out = null;
        try {
            ZipFile zip = new ZipFile(frameFile);
            ZipEntry entry = zip.getEntry("resources.arsc");

            if (entry == null) {
                throw new AndrolibException("Can't find resources.arsc file");
            }

            in = zip.getInputStream(entry);
            byte[] data = IOUtils.toByteArray(in);

//            解码ARSCData
            ARSCData arsc = ARSCDecoder.decode(new ByteArrayInputStream(data), true, true);
//            公有资源
            publicizeResources(data, arsc.getFlagsOffsets());

            File outFile = new File(getFrameworkDir(), String.valueOf(arsc
                .getOnePackage().getId())
                + (tag == null ? "" : '-' + tag)
                + ".apk");

            out = new ZipOutputStream(new FileOutputStream(outFile));
            out.setMethod(ZipOutputStream.STORED);
            CRC32 crc = new CRC32();
            crc.update(data);
            entry = new ZipEntry("resources.arsc");
            entry.setSize(data.length);
            entry.setMethod(ZipOutputStream.STORED);
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            out.write(data);
            out.closeEntry();

            //Write fake AndroidManifest.xml file to support original aapt
//            写假AndroidManifest.xml文件来支持原来的aapt
            entry = zip.getEntry("AndroidManifest.xml");
            if (entry != null) {
                in = zip.getInputStream(entry);
                byte[] manifest = IOUtils.toByteArray(in);
                CRC32 manifestCrc = new CRC32();
                manifestCrc.update(manifest);
                entry.setSize(manifest.length);
                entry.setCompressedSize(-1);
                entry.setCrc(manifestCrc.getValue());
                out.putNextEntry(entry);
                out.write(manifest);
                out.closeEntry();
            }

            zip.close();
            LOGGER.info("Framework installed to: " + outFile);
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        } finally {
            IOUtils.closeQuietly(in);
            IOUtils.closeQuietly(out);
        }
    }

    /**
     * publicizeResources 还在更新
     *
     * @param arscFile
     * @throws AndrolibException
     */
    public void publicizeResources(File arscFile) throws AndrolibException {
        byte[] data = new byte[(int) arscFile.length()];

        try (InputStream in = new FileInputStream(arscFile);
             OutputStream out = new FileOutputStream(arscFile)) {
            in.read(data);
            publicizeResources(data);
            out.write(data);
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        }
    }

    public void publicizeResources(byte[] arsc) throws AndrolibException {
        publicizeResources(arsc, ARSCDecoder.decode(new ByteArrayInputStream(arsc), true, true).getFlagsOffsets());
    }

    public void publicizeResources(byte[] arsc, FlagsOffset[] flagsOffsets) {
        for (FlagsOffset flags : flagsOffsets) {
            int offset = flags.offset + 3;
            int end = offset + 4 * flags.count;
            while (offset < end) {
                arsc[offset] |= (byte) 0x40;
                offset += 4;
            }
        }
    }

    /**
     * 框架文件所在文件夹
     *
     * @return File
     * @throws AndrolibException 自定义异常
     */
    public File getFrameworkDir() throws AndrolibException {
        if (mFrameworkDirectory != null) {
            return mFrameworkDirectory;
        }

        String path;

        // if a framework path was specified on the command line, use it
//        如果在命令行上指定了框架路径，则使用它
        if (apkOptions.frameworkFolderLocation != null) {
            path = apkOptions.frameworkFolderLocation;
        } else {
            File parentPath = new File(System.getProperty("user.home"));

            if (OSDetection.isMacOSX()) {
//                MacOS
                path = parentPath.getAbsolutePath() + String.format("%1$sLibrary%1$sapktool%1$sframework", File.separatorChar);
            } else if (OSDetection.isWindows()) {
//                WindowOs
                path = parentPath.getAbsolutePath() + String.format("%1$sAppData%1$sLocal%1$sapktool%1$sframework", File.separatorChar);
            } else {
                path = parentPath.getAbsolutePath() + String.format("%1$s.local%1$sshare%1$sapktool%1$sframework", File.separatorChar);
            }
        }

        File dir = new File(path);

        if (!dir.isDirectory() && dir.isFile()) {
            throw new AndrolibException("--frame-path is set to a file, not a directory.");
        }

        if (dir.getParentFile() != null && dir.getParentFile().isFile()) {
            throw new AndrolibException("Please remove file at " + dir.getParentFile());
        }

        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                if (apkOptions.frameworkFolderLocation != null) {
                    LOGGER.severe("Can't create Framework directory: " + dir);
                }
                throw new AndrolibException(String.format(
                    "Can't create directory: (%s). Pass a writable path with --frame-path {DIR}. ", dir
                ));
            }
        }

        if (apkOptions.frameworkFolderLocation == null) {
            if (!dir.canWrite()) {
                LOGGER.severe(String.format("WARNING: Could not write to (%1$s), using %2$s instead...",
                    dir.getAbsolutePath(), System.getProperty("java.io.tmpdir")));
                LOGGER.severe("Please be aware this is a volatile directory and frameworks could go missing, " +
                    "please utilize --frame-path if the default storage directory is unavailable");

                dir = new File(System.getProperty("java.io.tmpdir"));
            }
        }

        mFrameworkDirectory = dir;
        return dir;
    }

    /**
     * 获取AAPT二进制文件
     *
     * @return File
     * @throws AndrolibException 自定义异常
     */
    private File getAaptBinaryFile() throws AndrolibException {
        try {
            if (getAaptVersion() == 2) {
                return AaptManager.getAapt2();
            }
            return AaptManager.getAapt1();
        } catch (BrutException ex) {
            throw new AndrolibException(ex);
        }
    }

    /**
     * 使用的AAPT版本
     *
     * @return int
     */
    private int getAaptVersion() {
        return apkOptions.isAapt2() ? 2 : 1;
    }

    /**
     * 从jar的resources下拿到 android-framework.jar
     *
     * @return InputStream
     */
    public InputStream getAndroidFrameworkResourcesAsStream() {
        return Jar.class.getResourceAsStream("/brut/androlib/android-framework.jar");
    }

    public void close() throws IOException {
        if (mFramework != null) {
            mFramework.close();
        }
    }

    public ApkOptions apkOptions;

    // TODO: dirty static hack. I have to refactor decoding mechanisms.
    public static boolean sKeepBroken = false;

    private final static Logger LOGGER = Logger.getLogger(AndrolibResources.class.getName());

    private File mFrameworkDirectory = null;

    private ExtFile mFramework = null;

    private String mMinSdkVersion = null;
    private String mMaxSdkVersion = null;
    private String mTargetSdkVersion = null;
    private String mVersionCode = null;
    private String mVersionName = null;
    private String mPackageRenamed = null;
    private String mPackageId = null;

    private boolean mSharedLibrary = false;
    private boolean mSparseResources = false;

    /**
     * 忽略的包
     */
    private final static String[] IGNORED_PACKAGES = new String[]{
        "android", "com.htc", "com.lge", "com.lge.internal", "yi", "flyme", "air.com.adobe.appentry",
        "FFFFFFFFFFFFFFFFFFFFFF"};
}
