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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class CloudStoreClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("cloudstore");
    public static CloudStoreConfig config;
    public static CloudStoreApi api;
    public static InventoryCache cache;
    private static KeyBinding openKey;
    private static boolean loginInProgress;
    private static long connectionId;

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

    private static void loadServerInventory(long loginConnectionId, CloudStoreModels.MeResponse me) {
        cache.putMe(config.apiBaseUrl, me);
        String ownerUuid = me.defaultOwnerUuid;
        if ((ownerUuid == null || ownerUuid.isBlank()) && me.owners != null && !me.owners.isEmpty()) {
            ownerUuid = me.owners.getFirst().ownerUuid;
        }
        if ((ownerUuid == null || ownerUuid.isBlank()) && me.user != null) ownerUuid = me.user.uuid;
        if (ownerUuid == null || ownerUuid.isBlank()) return;
        String requestedOwner = ownerUuid;
        api.inventory(requestedOwner).whenComplete((inventory, error) -> {
            if (loginConnectionId != connectionId || error != null) return;
            cache.putInventory(config.apiBaseUrl, requestedOwner, inventory);
        });
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
