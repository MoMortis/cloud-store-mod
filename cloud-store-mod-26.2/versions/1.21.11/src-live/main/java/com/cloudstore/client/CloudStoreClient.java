package com.cloudstore.client;

import com.cloudstore.client.api.CloudStoreApi;
import com.cloudstore.client.cache.InventoryCache;
import com.cloudstore.client.config.CloudStoreConfig;
import com.cloudstore.client.api.CloudStoreModels;
import com.cloudstore.client.screen.CloudStoreScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CloudStoreClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("cloudstore");
    public static CloudStoreConfig config;
    public static CloudStoreApi api;
    public static InventoryCache cache;
    private static KeyBinding openKey;
    private static boolean loginInProgress;
    private static long connectionId;
    // 同一仓库的库存请求全局只保留一个，反复开关界面不会堆积
    private static final Map<String, CompletableFuture<CloudStoreModels.InventoryResponse>> PENDING_INVENTORY = new ConcurrentHashMap<>();
    // 每个仓库最近一次写入缓存的结果序号，迟到的旧响应不覆盖新数据
    private static final Map<String, Long> CACHE_SEQUENCE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_SEQUENCE_SOURCE = new AtomicLong();

    @Override
    public void onInitializeClient() {
        config = CloudStoreConfig.load();
        api = new CloudStoreApi(config);
        cache = InventoryCache.load();
        LOGGER.info("Cloud Store client initialized, endpoint={}", config.apiBaseUrl);
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.cloudstore.open",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            KeyBinding.Category.create(Identifier.of("cloudstore", "main"))
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                open(client);
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> startServerLogin(++connectionId));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            connectionId++;
            loginInProgress = false;
        });
    }

    public static void open(MinecraftClient client) {
        if (client.currentScreen instanceof CloudStoreScreen) {
            return;
        }
        client.setScreen(new CloudStoreScreen(client.currentScreen));
    }

    public static boolean sendChatCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            showStatus("未连接服务器，无法发送登录私聊。");
            return false;
        }
        String message = command == null ? "" : command.trim();
        if (message.isEmpty()) return false;
        if (message.startsWith("/")) client.getNetworkHandler().sendChatCommand(message.substring(1));
        else client.getNetworkHandler().sendChatMessage(message);
        return true;
    }

    private static void startServerLogin(long loginConnectionId) {
        if (loginInProgress) return;
        loginInProgress = true;
        api.connect().whenComplete((me, connectError) -> {
            if (loginConnectionId != connectionId) return;
            if (connectError == null) {
                loginInProgress = false;
                showStatus("云仓库已登录");
                loadServerInventory(loginConnectionId, me);
                return;
            }
            api.startLoginChallenge().whenComplete((challenge, challengeError) -> MinecraftClient.getInstance().execute(() -> {
                if (challengeError != null || challenge == null || challenge.command == null
                    || challenge.command.isBlank() || !sendChatCommand(challenge.command)) {
                    loginInProgress = false;
                    showStatus(challengeError == null ? "云仓库自动登录失败，请按 O 手动登录。" : challengeError.getMessage());
                    return;
                }
                showStatus("已发送云仓库登录验证，正在等待确认...");
                pollServerLogin(loginConnectionId);
            }));
        });
    }

    private static void pollServerLogin(long loginConnectionId) {
        api.loginChallengeStatus().whenComplete((challenge, error) -> MinecraftClient.getInstance().execute(() -> {
            if (loginConnectionId != connectionId) return;
            if (error != null || challenge == null) {
                loginInProgress = false;
                showStatus(error == null ? "云仓库自动登录失败，请按 O 手动登录。" : error.getMessage());
                return;
            }
            if (challenge.loggedIn || "approved".equals(challenge.status)) {
                loginInProgress = false;
                showStatus("云仓库登录成功");
                api.connect().whenComplete((me, connectError) -> {
                    if (loginConnectionId != connectionId || connectError != null) return;
                    loadServerInventory(loginConnectionId, me);
                });
                return;
            }
            if ("expired".equals(challenge.status) || "cancelled".equals(challenge.status) || "none".equals(challenge.status)) {
                loginInProgress = false;
                showStatus("云仓库登录验证失效，请按 O 重试。");
                return;
            }
            CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> pollServerLogin(loginConnectionId));
        }));
    }

    public static boolean hasPendingInventory(String ownerUuid) {
        return ownerUuid != null && !ownerUuid.isBlank() && PENDING_INVENTORY.containsKey(cacheKey(ownerUuid));
    }

    public static CompletableFuture<CloudStoreModels.InventoryResponse> fetchInventory(String ownerUuid) {
        String key = cacheKey(ownerUuid);
        CompletableFuture<CloudStoreModels.InventoryResponse> pending = PENDING_INVENTORY.get(key);
        if (pending != null) return pending;
        long sequence = CACHE_SEQUENCE_SOURCE.incrementAndGet();
        CompletableFuture<CloudStoreModels.InventoryResponse> future;
        try {
            future = api.inventory(ownerUuid);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        pending = PENDING_INVENTORY.putIfAbsent(key, future);
        if (pending != null) return pending;
        future.whenComplete((response, error) -> {
            PENDING_INVENTORY.remove(key, future);
            if (error == null && response != null) storeInventory(key, sequence, response);
        });
        return future;
    }

    private static String cacheKey(String ownerUuid) {
        return config.apiBaseUrl + '\u0000' + ownerUuid;
    }

    // 只有序号更大的结果才写缓存，迟到的旧响应当丢弃
    private static void storeInventory(String key, long sequence, CloudStoreModels.InventoryResponse response) {
        boolean[] newest = {false};
        CACHE_SEQUENCE.compute(key, (ignored, previous) -> {
            if (previous != null && previous >= sequence) return previous;
            newest[0] = true;
            return sequence;
        });
        if (!newest[0]) return;
        int split = key.indexOf('\u0000');
        cache.putInventory(key.substring(0, split), key.substring(split + 1), response);
    }

    private static void loadServerInventory(long loginConnectionId, CloudStoreModels.MeResponse me) {
        cache.putMe(config.apiBaseUrl, me);
        String ownerUuid = me.defaultOwnerUuid;
        if ((ownerUuid == null || ownerUuid.isBlank()) && me.owners != null && !me.owners.isEmpty()) {
            ownerUuid = me.owners.getFirst().ownerUuid;
        }
        if ((ownerUuid == null || ownerUuid.isBlank()) && me.user != null) ownerUuid = me.user.uuid;
        if (ownerUuid == null || ownerUuid.isBlank()) return;
        String requestedOwner = ownerUuid;
        fetchInventory(requestedOwner);
    }

    public static void showStatus(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[云仓库] " + message), true);
        }
    }

    public static Identifier id(String path) {
        return Identifier.of("cloudstore", path);
    }
}
