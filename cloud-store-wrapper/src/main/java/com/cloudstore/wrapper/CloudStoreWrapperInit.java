package com.cloudstore.wrapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cloud Store versionpack 壳（cloudstore-wrapper）的启动校验，与 litematica-DSH /
 * itemscroller-DSH / ViaFabricPlus-Mortis 的壳校验同构。
 *
 * 背景：DSH 改版与 damomo 原版共用 mod id「cloudstore」。Fabric Loader 对同 id 多候选
 * 不报错，交给 SAT 求解器按"版本高者优先"择优——DSH 改版版本号（1.0 / 0.3.0）目前压过
 * 原版（0.2.2）所以原版被静默忽略；但原版哪天发 1.1+ 就会反过来把 DSH 顶掉，用户毫无感知。
 *
 * 两层校验，任一命中直接抛异常让游戏崩溃报错：
 * 1. 生效方校验：当前生效的 cloudstore 必须携带 DSH 独有标记类
 *    （client.screen.SchematicImportBrowser，DSH 改版新增的原理图导入浏览器，三个分支
 *    嵌套 jar 全都携带；damomo 原版没有）。
 * 2. mods 目录扫描：任何顶层 jar 的 fabric.mod.json 声明 id 为「cloudstore」即为外来
 *    Cloud Store（DSH 的只作为嵌套 jar 生效，永远不会是顶层 jar）——即使这次没生效也照报。
 *
 * 另见 printPclDuplicateMarker：给 PCL 启动器的崩溃分析规则打标记，让弹窗说真话。
 *
 * 注意：字节码保持 Java 8（--release 8）；壳不依赖 MC 类，三个 MC 分支通用。
 */
public final class CloudStoreWrapperInit implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudstore-wrapper");
    private static final String CLOUDSTORE_ID = "cloudstore";
    private static final String WRAPPER_ID = "cloudstore-wrapper";
    /** DSH 改版独有类（原理图导入浏览器）；damomo 原版没有 */
    private static final String DSH_MARKER_CLASS = "com.cloudstore.client.screen.SchematicImportBrowser";

    /** 收集到的冲突 jar 文件名（DSH 自己 + 外来者），供 PCL 标记行使用 */
    private static final List<String> DUPLICATE_JARS = new ArrayList<>();

    @Override
    public void onInitialize() {
        List<String> problems = new ArrayList<>();
        addOwnJarName();
        checkActiveCloudStore(problems);
        checkModsDirectory(problems);

        if (!problems.isEmpty()) {
            // 第一行就点明"装了两个 Cloud Store"，细节缩进降级；同时往日志打一条 ERROR，
            // 崩溃界面看不全长消息时，日志里一定看得全
            String message =
                "这个崩溃是 mods 目录里同时装了两个 Cloud Store（云商店）模组导致的！两个只能留一个。\n"
                + "原因：\n" + String.join("\n", problems) + "\n"
                + "处置：删除多余的 cloudstore jar（如 damomo-cloud-store-*.jar 等原版），"
                + "只保留 Cloud-Store.jar，重启游戏。";
            LOGGER.error("[Cloud-Store] {}", message);
            printPclDuplicateMarker();
            throw new IllegalStateException(message);
        }

        LOGGER.info("[Cloud-Store] 启动校验通过：当前生效的 Cloud Store 是 DSH 嵌套 jar，mods 目录无外来版本");
    }

    /**
     * 给 PCL 启动器的崩溃分析规则打标记：PCL（ModCrash.vb）只要在日志里看到
     * "ModResolutionException: Duplicate" 就判定为「重复安装了多个相同的 Mod」，并从该行提取
     * jar 文件名列进弹窗（"你重复安装了多个相同的 Mod：- xxx.jar……请删除重复的 Mod"）。
     * 壳的校验发现的正是"同一 mod id 装了两份"，打这行标记能让 PCL 弹窗说真话，
     * 而不是按堆栈归属把锅甩给抛异常的壳（"名为 Cloud Store 的 Mod 导致了游戏出错，
     * 可以尝试禁用此 Mod"——误导用户禁用 DSH 改版）。
     */
    private static void printPclDuplicateMarker() {
        String jars = DUPLICATE_JARS.isEmpty() ? "（无法定位具体 jar 文件名）" : String.join(" + ", DUPLICATE_JARS);
        LOGGER.error(
            "net.fabricmc.loader.impl.ModResolutionException: Duplicate mod id 'cloudstore' from mod files: {}"
            + " —— PCL 提示标记：由 Cloud-Store 的模组冲突启动校验主动触发，并非 loader 原生异常",
            jars
        );
    }

    /** 把壳自己的 jar 文件名记进冲突清单（优先展示"要保留的那个"） */
    private static void addOwnJarName() {
        Optional<ModContainer> wrapper = FabricLoader.getInstance().getModContainer(WRAPPER_ID);
        if (wrapper.isPresent()) {
            String name = outerJarName(wrapper.get());
            if (name != null && !DUPLICATE_JARS.contains(name)) {
                DUPLICATE_JARS.add(name);
            }
        }
    }

    /** 校验 1：当前生效的 cloudstore 是不是 DSH（被顶掉时 DSH 代码不在 classpath，标记类缺失） */
    private static void checkActiveCloudStore(List<String> problems) {
        Optional<ModContainer> cloudstore = FabricLoader.getInstance().getModContainer(CLOUDSTORE_ID);
        if (!cloudstore.isPresent()) {
            return; // 依赖解析阶段 loader 会先因壳的 depends 报错，正常走不到这里
        }

        try {
            Class.forName(DSH_MARKER_CLASS, false, CloudStoreWrapperInit.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            String jarName = outerJarName(cloudstore.get());
            if (jarName != null && !DUPLICATE_JARS.contains(jarName)) {
                DUPLICATE_JARS.add(jarName);
            }
            problems.add(
                "- 当前生效的 Cloud Store 是 " + cloudstore.get().getMetadata().getVersion().getFriendlyString()
                + "（不是 DSH 改版）：原版把 DSH 改版顶掉了，DSH 的功能全部没生效。"
            );
        }
    }

    /** 校验 2：mods 目录里不允许出现任何顶层「cloudstore」（DSH 的只会以嵌套 jar 形式存在） */
    private static void checkModsDirectory(List<String> problems) {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return;
        }

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(modsDir)) {
            files.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".jar")).forEach(f -> {
                if (CLOUDSTORE_ID.equals(readTopLevelModId(f.toFile()))) {
                    offenders.add(f.getFileName().toString());
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[Cloud-Store] 扫描 mods 目录失败，跳过外来模组检查：{}", e.toString());
        }

        if (!offenders.isEmpty()) {
            for (String name : offenders) {
                if (!DUPLICATE_JARS.contains(name)) {
                    DUPLICATE_JARS.add(name);
                }
            }
            problems.add(
                "- mods 目录里发现其他版本的 Cloud Store jar：" + offenders
                + "（即使这次没生效，版本号一高就会顶掉 DSH 改版）。"
            );
        }
    }

    /** 读顶层 jar 的 fabric.mod.json，返回其 mod id；不是合法 fabric mod 则返回 null */
    private static String readTopLevelModId(File file) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                return null;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            InputStream in = zip.getInputStream(entry);
            try {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            } finally {
                in.close();
            }

            JsonObject root = JsonParser.parseString(new String(out.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            return root.has("id") && !root.get("id").isJsonNull() ? root.get("id").getAsString() : null;
        } catch (Exception e) {
            return null; // 非 fabric jar / 损坏 jar 一律忽略
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 尽力从 ModContainer 的根路径还原外层 jar 文件名。
     * 顶层 mod：根路径就是 jar 文件本身；嵌套 jar：路径形如 "外层.jar!/META-INF/jars/内层.jar"
     * 或 zipfs 的 "外层.jar/META-INF/jars/内层.jar"，统一截到外层。失败返回 null。
     */
    private static String outerJarName(ModContainer container) {
        try {
            for (Path root : container.getRootPaths()) {
                String s = root.toString();
                int cut = s.indexOf("!/");
                if (cut < 0) {
                    cut = s.indexOf("/META-INF/jars/");
                }
                if (cut >= 0) {
                    s = s.substring(0, cut);
                }
                Path fileName = java.nio.file.Paths.get(s).getFileName();
                if (fileName != null && fileName.toString().toLowerCase().endsWith(".jar")) {
                    return fileName.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
