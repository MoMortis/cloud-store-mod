package com.cloudstore.client.screen;

import com.cloudstore.client.CloudStoreClient;
import com.cloudstore.client.api.CloudStoreModels;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.BlockItem;
import net.minecraft.block.FluidBlock;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CloudStoreScreen extends Screen {
    private static final int TILE_SIZE = 34;
    private static final int TILE_STEP = 34;
    private static final float ITEM_SCALE = 1.25f;
    private static final float HOVER_ITEM_SCALE = 2.0f;
    private static final int TAB_SIZE = 24;
    private static final int TAB_STEP = 26;

    private final Screen parent;
    private final List<CloudStoreModels.Item> items = new ArrayList<>();
    private final Map<String, SearchTerms> searchIndex = new HashMap<>();
    private final Map<String, Long> selected = new LinkedHashMap<>();
    private final Map<String, List<SourceSelection>> selectedSources = new LinkedHashMap<>();
    private final List<Category> categories = new ArrayList<>();
    private CloudStoreModels.MeResponse me;
    private CloudStoreModels.InventoryResponse lastInventory;
    private String ownerUuid = "";
    private int ownerIndex;
    private int selectedCategory;
    private int scroll;
    private float scrollAnim;
    // 每个物品的悬停缩放动画值（itemKey → 当前缩放）
    private final java.util.Map<String, Float> hoverScales = new java.util.HashMap<>();
    private int gridY;
    private int gridBottom;
    private int tabY = 55;
    private boolean initialized;
    private boolean busy;
    private boolean submitting;
    private String status = "";
    private int statusColor = 0xFFF1B36B;
    // 原理图导入后仓库无法满足的物品明细，鼠标悬停状态栏时展开显示
    private List<Text> importIssueTooltip = List.of();
    private String importIssueStatus = "";
    // 在飞的库存刷新请求数 / 请求序号，序号用于丢弃迟到的旧响应
    private int inventoryInFlight;
    private long inventoryRequestSeq;
    private long appliedInventorySeq;
    private String savedSearch = "";
    private String savedTarget = "";
    private List<String> targetSuggestions = List.of();
    private int targetSuggestionIndex;
    private int targetSuggestionScroll;
    private boolean targetSuggestionsOpen;
    private TextFieldWidget searchField;
    private TextFieldWidget targetField;
    private ButtonWidget ownerButton;
    private ButtonWidget schematicButton;
    private ButtonWidget loginButton;
    private ButtonWidget depositButton;
    private ButtonWidget refreshButton;
    private ButtonWidget submitButton;
    private ButtonWidget clearButton;

    public CloudStoreScreen(Screen parent) {
        super(Text.literal("云仓库"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (searchField != null) savedSearch = searchField.getText();
        if (targetField != null) savedTarget = targetField.getText();

        boolean narrowToolbar = this.width < 500;
        int searchWidth = narrowToolbar ? Math.max(80, this.width - 116) : Math.min(180, Math.max(100, this.width / 4));
        searchField = new TextFieldWidget(this.textRenderer, 10, 28, searchWidth, 20, Text.literal("搜索物品"));
        searchField.setPlaceholder(Text.literal("搜索名称 / ID / 短码"));
        searchField.setMaxLength(80);
        searchField.setText(savedSearch);
        searchField.setChangedListener(value -> {
            savedSearch = value;
            scroll = 0;
        });
        this.addDrawableChild(searchField);

        int ownerX = narrowToolbar ? 10 : 16 + searchWidth;
        int rightToolbarWidth = 106;
        int schematicWidth = 76;
        int ownerWidth = narrowToolbar
            ? Math.max(70, this.width - ownerX - schematicWidth - 12)
            : Math.max(70, Math.min(130, this.width - ownerX - rightToolbarWidth - schematicWidth - 16));
        int toolbarY = narrowToolbar ? 52 : 28;
        ownerButton = ButtonWidget.builder(Text.literal("选择仓库"), button -> cycleOwner())
            .dimensions(ownerX, toolbarY, ownerWidth, 20).build();
        this.addDrawableChild(ownerButton);
        schematicButton = ButtonWidget.builder(Text.literal("导入原理图"), button -> openSchematicBrowser())
            .dimensions(ownerX + ownerWidth + 8, toolbarY, schematicWidth, 20).build();
        this.addDrawableChild(schematicButton);
        refreshButton = ButtonWidget.builder(Text.literal("刷新"), button -> reload())
            .dimensions(this.width - 106, 28, 48, 20).build();
        this.addDrawableChild(refreshButton);
        depositButton = ButtonWidget.builder(Text.literal("存入"), button -> deposit())
            .dimensions(this.width - 52, 28, 42, 20).build();
        this.addDrawableChild(depositButton);
        tabY = narrowToolbar ? 79 : 55;

        boolean twoRowBottom = this.width < 390;
        int bottomY = this.height - 27;
        int actionWidth = this.width < 430 ? 78 : 96;
        int targetY = twoRowBottom ? bottomY - 26 : bottomY;
        int targetWidth = twoRowBottom ? this.width - 20 : Math.min(150, Math.max(86, this.width - actionWidth - 170));
        targetField = new TextFieldWidget(this.textRenderer, 10, targetY, targetWidth, 20, Text.literal("领取玩家"));
        targetField.setPlaceholder(Text.literal("留空=自己；可填代取 ID"));
        targetField.setMaxLength(16);
        targetField.setEditableColor(0xFFFFFFFF);
        targetField.setText(savedTarget);
        targetField.setChangedListener(value -> {
            savedTarget = value;
            refreshTargetSuggestions();
        });
        this.addDrawableChild(targetField);

        int closeX = this.width - 44;
        int settingsX = closeX - 52;
        int compactActionWidth = twoRowBottom ? 66 : actionWidth;
        int submitX = settingsX - compactActionWidth - 6;
        int clearX = twoRowBottom ? submitX - 48 - 6 : submitX - 48 - 6;
        clearButton = ButtonWidget.builder(Text.literal("清空"), button -> clearSelection())
            .dimensions(clearX, bottomY, 48, 20).build();
        this.addDrawableChild(clearButton);
        submitButton = ButtonWidget.builder(Text.literal("提交取货"), button -> submitWithdraw())
            .dimensions(submitX, bottomY, compactActionWidth, 20).build();
        this.addDrawableChild(submitButton);
        loginButton = ButtonWidget.builder(Text.literal("登录"), button -> startLogin())
            .dimensions(settingsX, bottomY, 46, 20).build();
        this.addDrawableChild(loginButton);
        this.addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), button -> close())
            .dimensions(closeX, bottomY, 34, 20).build());

        if (!initialized) {
            initialized = true;
            restoreCachedData();
        } else {
            updateButtons();
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (targetField != null && targetField.isFocused() && input.key() == GLFW.GLFW_KEY_TAB) {
            refreshTargetSuggestions();
            if (!targetSuggestions.isEmpty()) {
                completeTarget(input.modifiers() > 0);
                return true;
            }
        }
        return super.keyPressed(input);
    }

    private void refreshTargetSuggestions() {
        if (targetField == null || client == null || client.getNetworkHandler() == null) {
            targetSuggestions = List.of();
            targetSuggestionIndex = 0;
            targetSuggestionScroll = 0;
            targetSuggestionsOpen = false;
            return;
        }
        String prefix = targetField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        targetSuggestions = client.getNetworkHandler().getPlayerList().stream()
            .map(info -> info.getProfile().name())
            .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        targetSuggestionIndex = Math.min(targetSuggestionIndex, Math.max(0, targetSuggestions.size() - 1));
        targetSuggestionScroll = Math.min(targetSuggestionScroll, Math.max(0, targetSuggestions.size() - 6));
        targetSuggestionsOpen = targetField.isFocused() && !targetSuggestions.isEmpty();
    }

    private void completeTarget(boolean reverse) {
        targetSuggestionIndex = Math.floorMod(targetSuggestionIndex + (reverse ? -1 : 1), targetSuggestions.size());
        keepTargetSuggestionVisible();
        targetField.setText(targetSuggestions.get(targetSuggestionIndex));
        targetField.setCursorToEnd(false);
        targetSuggestionsOpen = true;
    }

    private void keepTargetSuggestionVisible() {
        if (targetSuggestionIndex < targetSuggestionScroll) targetSuggestionScroll = targetSuggestionIndex;
        if (targetSuggestionIndex >= targetSuggestionScroll + 6) targetSuggestionScroll = targetSuggestionIndex - 5;
        targetSuggestionScroll = Math.max(0, Math.min(targetSuggestionScroll, Math.max(0, targetSuggestions.size() - 6)));
    }

    private void renderTargetSuggestions(DrawContext context, int mouseX, int mouseY) {
        if (!targetSuggestionsOpen || targetSuggestions.isEmpty()) return;
        int rows = Math.min(6, targetSuggestions.size() - targetSuggestionScroll);
        int x = targetField.getX();
        int y = targetField.getY() - rows * 18 - 2;
        int width = targetField.getWidth();
        context.fill(x, y, x + width, targetField.getY() - 1, 0xF0101014);
        for (int i = 0; i < rows; i++) {
            int index = targetSuggestionScroll + i;
            int rowY = y + i * 18;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + 18;
            context.fill(x, rowY, x + width, rowY + 18, hovered || index == targetSuggestionIndex ? 0xFF356A52 : 0xFF242A33);
            context.drawTextWithShadow(textRenderer, Text.literal(targetSuggestions.get(index)), x + 5, rowY + 5, 0xFFFFFFFF);
        }
    }

    private boolean clickTargetSuggestion(double mouseX, double mouseY) {
        if (!targetSuggestionsOpen || targetSuggestions.isEmpty()) return false;
        int rows = Math.min(6, targetSuggestions.size() - targetSuggestionScroll);
        int x = targetField.getX();
        int y = targetField.getY() - rows * 18 - 2;
        if (mouseX < x || mouseX >= x + targetField.getWidth() || mouseY < y || mouseY >= targetField.getY()) return false;
        int index = targetSuggestionScroll + (int) ((mouseY - y) / 18.0);
        if (index < 0 || index >= targetSuggestions.size()) return false;
        targetSuggestionIndex = index;
        targetField.setText(targetSuggestions.get(index));
        targetField.setFocused(true);
        setFocused(targetField);
        targetField.setCursorToEnd(false);
        targetSuggestionsOpen = false;
        return true;
    }

    public void setSelectedAmount(CloudStoreModels.Item item, long amount) {
        if (amount <= 0) selected.remove(item.itemKey);
        else selected.put(item.itemKey, Math.min(amount, item.amount));
        updateButtons();
    }

    public void reload() {
        CloudStoreClient.showStatus("尝试刷新");
        // 还没解析出仓库时单纯拉库存会被空 owner 挡掉，改走完整重连
        if (ownerUuid.isBlank()) {
            load();
            return;
        }
        loadInventoryOnly(true, true);
    }

    private String endpoint() {
        return CloudStoreClient.config.apiBaseUrl;
    }

    private void restoreCachedData() {
        CloudStoreModels.MeResponse cachedMe = CloudStoreClient.cache.me(endpoint());
        if (cachedMe == null) {
            load();
            return;
        }
        me = cachedMe;
        ownerUuid = resolveOwner(cachedMe, ownerUuid);
        CloudStoreModels.InventoryResponse cachedInventory = CloudStoreClient.cache.inventory(endpoint(), ownerUuid);
        // 没有缓存或连仓库都没解析出来时走完整加载，别在空 owner 上原地卡住
        if (cachedInventory == null || ownerUuid.isBlank()) {
            load();
            return;
        }
        applyInventory(cachedInventory);
        status = "已显示缓存，关闭界面时自动刷新";
        updateButtons();
        // 上次关闭界面发出的刷新如果还没回来，挂上去等结果；不会额外发请求
        if (CloudStoreClient.hasPendingInventory(ownerUuid)) {
            loadInventoryOnly(false, true);
        }
    }

    private void load() {
        if (busy) return;
        busy = true;
        long requestSeq = ++inventoryRequestSeq;
        if (items.isEmpty()) status = "正在连接云仓库...";
        else status = "正在后台刷新...";
        updateButtons();
        String preferredOwner = ownerUuid;
        CloudStoreClient.api.connect().thenCompose(response -> {
            CloudStoreClient.cache.putMe(endpoint(), response);
            String resolvedOwner = resolveOwner(response, preferredOwner);
            return CloudStoreClient.fetchInventory(resolvedOwner)
                .thenApply(inventory -> new LoadResult(response, resolvedOwner, inventory));
        }).whenComplete((result, error) -> MinecraftClient.getInstance().execute(() -> {
            busy = false;
            if (error != null) {
                status = message(error);
                updateButtons();
                return;
            }
            me = result.me;
            // 以用户此刻选中的仓库为准，避免把中途切换的选择改回去
            ownerUuid = resolveOwner(result.me, ownerUuid);
            if (ownerUuid.equals(result.ownerUuid)) {
                if (requestSeq > appliedInventorySeq) {
                    appliedInventorySeq = requestSeq;
                    applyInventory(result.inventory);
                }
                status = "";
                updateButtons();
            } else {
                // 加载期间切了仓库，重新拉当前这个
                updateButtons();
                loadInventoryOnly(false, true);
            }
        }));
    }

    private String resolveOwner(CloudStoreModels.MeResponse response, String preferredOwner) {
        if (response.owners == null || response.owners.isEmpty()) {
            ownerIndex = 0;
            return response.user == null ? "" : response.user.uuid;
        }
        for (int i = 0; i < response.owners.size(); i++) {
            CloudStoreModels.Owner owner = response.owners.get(i);
            if (owner.ownerUuid.equals(preferredOwner)) {
                ownerIndex = i;
                return owner.ownerUuid;
            }
        }
        for (int i = 0; i < response.owners.size(); i++) {
            CloudStoreModels.Owner owner = response.owners.get(i);
            if (owner.ownerUuid.equals(response.defaultOwnerUuid)) {
                ownerIndex = i;
                return owner.ownerUuid;
            }
        }
        ownerIndex = 0;
        return response.owners.getFirst().ownerUuid;
    }

    private void cycleOwner() {
        if (me == null || me.owners == null || me.owners.isEmpty()) return;
        ownerIndex = (ownerIndex + 1) % me.owners.size();
        ownerUuid = me.owners.get(ownerIndex).ownerUuid;
        selected.clear();
        selectedSources.clear();
        scroll = 0;
        CloudStoreModels.InventoryResponse cached = CloudStoreClient.cache.inventory(endpoint(), ownerUuid);
        if (cached != null) applyInventory(cached);
        else items.clear();
        // 切换仓库必须真的拉一次，不能被在飞的刷新挡掉
        loadInventoryOnly(false, true);
    }

    private void loadInventoryOnly() {
        loadInventoryOnly(false);
    }

    private void loadInventoryOnly(boolean showRefreshComplete) {
        loadInventoryOnly(showRefreshComplete, false);
    }

    private void loadInventoryOnly(boolean showRefreshComplete, boolean force) {
        if (ownerUuid.isBlank()) return;
        if (!force && inventoryInFlight > 0) return;
        long startedAt = showRefreshComplete ? System.nanoTime() : 0L;
        long requestSeq = ++inventoryRequestSeq;
        inventoryInFlight++;
        status = items.isEmpty() ? "正在读取库存..." : "已显示缓存，正在后台刷新...";
        statusColor = 0xFFF1B36B;
        updateButtons();
        String requestedOwner = ownerUuid;
        CloudStoreClient.fetchInventory(requestedOwner).whenComplete((response, error) -> {
            MinecraftClient.getInstance().execute(() -> {
                inventoryInFlight = Math.max(0, inventoryInFlight - 1);
                // 只认比已应用响应更新的结果，防止迟到的旧数据盖掉新数据
                if (requestSeq > appliedInventorySeq) {
                    if (error != null) {
                        // 还有更新的请求在飞时，旧请求的错误不往界面上报
                        if (requestSeq == inventoryRequestSeq) {
                            status = message(error);
                            statusColor = 0xFFF1B36B;
                        }
                    } else if (requestedOwner.equals(ownerUuid)) {
                        appliedInventorySeq = requestSeq;
                        applyInventory(response);
                        status = "";
                        statusColor = 0xFF00FF00;
                        if (showRefreshComplete) {
                            CloudStoreClient.showStatus("刷新完成（%.2f 秒）".formatted((System.nanoTime() - startedAt) / 1_000_000_000.0d));
                        }
                    }
                }
                updateButtons();
            });
        });
    }

    private void applyInventory(CloudStoreModels.InventoryResponse response) {
        lastInventory = response;
        items.clear();
        items.addAll(flattenInventory(response.items));
        items.sort(Comparator.comparing(item -> safe(item.displayName), String.CASE_INSENSITIVE_ORDER));
        rebuildSearchIndex();
        Map<String, Long> available = new HashMap<>();
        for (CloudStoreModels.Item item : items) available.put(item.itemKey, item.amount);
        selected.entrySet().removeIf(entry -> !available.containsKey(entry.getKey()));
        selected.replaceAll((key, amount) -> Math.min(amount, available.getOrDefault(key, amount)));
        selectedSources.keySet().removeIf(key -> !available.containsKey(key));
        rebuildCategories();
        scroll = Math.max(0, scroll);
    }

    private List<CloudStoreModels.Item> flattenInventory(List<CloudStoreModels.Item> source) {
        Map<String, CloudStoreModels.Item> flattened = new LinkedHashMap<>();
        for (CloudStoreModels.Item row : source == null ? List.<CloudStoreModels.Item>of() : source) {
            if (isShulkerBox(row.itemId) && isEmptyShulkerBox(row)) {
                CloudStoreModels.Item emptyBox = copyItem(row);
                emptyBox.displayName = "空盒";
                emptyBox.baseDisplayName = "空盒";
                mergeFlattened(flattened, emptyBox, emptyBox.amount, 0);
            } else if (isShulkerBox(row.itemId)) {
                for (CloudStoreModels.Item content : row.shulkerContents == null ? List.<CloudStoreModels.Item>of() : row.shulkerContents) {
                    mergeFlattened(flattened, content, 0, safeMultiply(content.amount, row.amount));
                }
            } else {
                mergeFlattened(flattened, row, row.amount, 0);
            }
        }
        return new ArrayList<>(flattened.values());
    }

    private void mergeFlattened(Map<String, CloudStoreModels.Item> rows, CloudStoreModels.Item source, long loose, long boxed) {
        String key = variantKey(source);
        CloudStoreModels.Item target = rows.computeIfAbsent(key, ignored -> copyItem(source));
        target.looseAmount += loose;
        target.boxedAmount += boxed;
        target.amount = target.looseAmount + target.boxedAmount;
    }

    private CloudStoreModels.Item copyItem(CloudStoreModels.Item source) {
        CloudStoreModels.Item copy = new CloudStoreModels.Item();
        copy.itemKey = variantKey(source);
        copy.itemId = safe(source.itemId);
        copy.displayName = preferredDisplayName(source);
        copy.baseDisplayName = safe(source.baseDisplayName);
        copy.adminDisplayName = safe(source.adminDisplayName);
        copy.globalAliases = source.globalAliases == null ? new ArrayList<>() : new ArrayList<>(source.globalAliases);
        copy.personalAliases = source.personalAliases == null ? new ArrayList<>() : new ArrayList<>(source.personalAliases);
        copy.personalAlias = safe(source.personalAlias);
        copy.personalDisplayName = safe(source.personalDisplayName);
        copy.nbtJson = safe(source.nbtJson);
        copy.metaJson = safe(source.metaJson);
        copy.shortCode = safe(source.shortCode);
        copy.slotCount = source.slotCount;
        copy.amount = source.amount;
        copy.looseAmount = source.looseAmount;
        copy.boxedAmount = source.boxedAmount;
        copy.shulkerContents = source.shulkerContents == null ? new ArrayList<>() : new ArrayList<>(source.shulkerContents);
        return copy;
    }

    private String variantKey(CloudStoreModels.Item item) {
        String itemKey = safe(item.itemKey);
        if (!itemKey.isBlank()) return itemKey;
        return safe(item.itemId) + "|nbt=" + safe(item.nbtJson) + "|meta=" + safe(item.metaJson);
    }

    private String preferredDisplayName(CloudStoreModels.Item item) {
        if (!safe(item.personalDisplayName).isBlank()) return item.personalDisplayName;
        if (!safe(item.personalAlias).isBlank()) return item.personalAlias;
        if (item.personalAliases != null) {
            for (String alias : item.personalAliases) if (!safe(alias).isBlank()) return alias;
        }
        if (item.globalAliases != null) {
            for (String alias : item.globalAliases) if (!safe(alias).isBlank()) return alias;
        }
        return safe(item.displayName).isBlank() ? item.itemId : item.displayName;
    }


    private boolean hasCustomData(CloudStoreModels.Item item) {
        String nbt = safe(item.nbtJson);
        String meta = safe(item.metaJson);
        return (!nbt.isBlank() && !"null".equalsIgnoreCase(nbt))
            || (!meta.isBlank() && !"null".equalsIgnoreCase(meta)
                && !"{\"components\":null,\"metadata\":null}".equals(meta));
    }

    private long safeMultiply(long left, long right) {
        if (left <= 0 || right <= 0) return 0;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private boolean isShulkerBox(String itemId) {
        return safe(itemId).endsWith("shulker_box");
    }

    private boolean isEmptyShulkerBox(CloudStoreModels.Item item) {
        return item.shulkerContents == null || item.shulkerContents.stream().noneMatch(content -> content != null && content.amount > 0);
    }

    private void rebuildCategories() {
        categories.clear();
        categories.add(new Category(Text.literal("全部"), new ItemStack(Items.CHEST), Set.of(), true, false));
        try {
            if (this.client != null && this.client.world != null && this.client.getNetworkHandler() != null) {
                ItemGroups.updateDisplayContext(
                    this.client.getNetworkHandler().getEnabledFeatures(),
                    false,
                    this.client.world.getRegistryManager()
                );
            }
            for (ItemGroup group : ItemGroups.getGroupsToDisplay()) {
                try {
                    ItemGroup.Type type = group.getType();
                    if (type == ItemGroup.Type.HOTBAR) continue; // 删除"已保存快捷栏"
                    if (type == ItemGroup.Type.SEARCH) continue; // 删除"搜索物品"目录（与"全部"重复）
                    if (type == ItemGroup.Type.INVENTORY) {
                        // "生存模式物品栏"改为收藏目录（图标不变）
                        categories.add(new Category(Text.literal("收藏"), group.getIcon(), Set.of(), false, true));
                        continue;
                    }
                    Set<Item> members = new HashSet<>();
                    for (ItemStack stack : group.getDisplayStacks()) members.add(stack.getItem());
                    categories.add(new Category(group.getDisplayName(), group.getIcon(), members, false, false));
                } catch (RuntimeException error) {
                    CloudStoreClient.LOGGER.warn("Unable to build creative category {}: {}", group.getDisplayName().getString(), error.getMessage());
                }
            }
        } catch (RuntimeException error) {
            CloudStoreClient.LOGGER.warn("Unable to build creative categories: {}", error.getMessage());
        }
        selectedCategory = Math.min(selectedCategory, Math.max(0, categories.size() - 1));
    }

    private void deposit() {
        if (submitting || ownerUuid.isBlank()) return;
        submitting = true;
        status = "正在提交存储...";
        updateButtons();
        CloudStoreClient.api.deposit(ownerUuid).whenComplete((response, error) -> MinecraftClient.getInstance().execute(() -> {
            submitting = false;
            if (error != null) status = message(error);
            else {
                status = response.message;
                CloudStoreClient.showStatus(response.message);
                loadInventoryOnly(false, true);
            }
            updateButtons();
        }));
    }

    private void startLogin() {
        if (busy || submitting) return;
        busy = true; status = "正在重新登录..."; updateButtons();
        CloudStoreClient.api.logout().handle((response, error) -> {
            CloudStoreClient.api.clearSession();
            return null;
        }).thenCompose(ignored -> CloudStoreClient.api.startLoginChallenge()).whenComplete((challenge, error) -> MinecraftClient.getInstance().execute(() -> {
            if (error != null || challenge.command == null || challenge.command.isBlank() || !CloudStoreClient.sendChatCommand(challenge.command)) {
                busy = false; status = error == null ? "服务器未返回登录指令。" : message(error); updateButtons(); return;
            }
            status = "已发送登录私聊，正在等待验证...";
            pollLoginChallenge();
        }));
    }

    private void pollLoginChallenge() {
        CloudStoreClient.api.loginChallengeStatus().whenComplete((challenge, error) -> MinecraftClient.getInstance().execute(() -> {
            if (error != null) { busy = false; status = message(error); updateButtons(); return; }
            if (challenge.loggedIn || "approved".equals(challenge.status)) { busy = false; status = "登录成功，正在读取库存..."; load(); return; }
            if ("expired".equals(challenge.status) || "cancelled".equals(challenge.status) || "none".equals(challenge.status)) { busy = false; status = "验证码已失效，请重新登录。"; updateButtons(); return; }
            java.util.concurrent.CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS).execute(this::pollLoginChallenge);
        }));
    }

    private void openSchematicBrowser() {
        if (submitting || items.isEmpty()) return;
        new SchematicImportBrowser(this).open();
    }

    // 原理图材料统计是异步的（DSH 分支跑在后台线程），用请求序号区分新旧请求；materialImportPending 为当前等待填表的请求
    private int materialImportToken;
    private int materialImportPending;

    void importSchematic(java.nio.file.Path file, boolean missingOnly) {
        try {
            fi.dy.masa.litematica.schematic.LitematicaSchematic schematic = SchematicImportBrowser.load(file);
            if (schematic == null) { status = "无法读取原理图：" + file.getFileName(); return; }
            Map<String, CloudStoreModels.Item> available = new HashMap<>();
            for (CloudStoreModels.Item item : items) available.put(item.itemId, item);
            // 仅导入缺失时：统计本地背包（含盔甲、副手、潜影盒内）已有的物品数量
            Map<String, Long> owned = missingOnly ? countLocalPlayerItems() : Map.of();
            // DSH 分支把方块统计放在后台线程，构造完立刻取列表必然是空的（官方版是同步统计、当场就有）
            // 因此先就地试一次，取不到就等统计完成的回调再来
            fi.dy.masa.litematica.materials.MaterialListSchematic materialList =
                new fi.dy.masa.litematica.materials.MaterialListSchematic(schematic, true);
            final int token = ++materialImportToken;
            materialImportPending = token;
            materialList.setCompletionListener(() -> applySchematicMaterials(token, materialList, available, owned, missingOnly, true));
            status = "正在统计材料……";
            applySchematicMaterials(token, materialList, available, owned, missingOnly, false);
        } catch (RuntimeException error) { status = "原理图导入失败：" + message(error); }
        updateButtons();
    }

    // 材料统计就绪后汇总需求并导入；旧的导入请求或重复回调直接丢弃
    private void applySchematicMaterials(int token, fi.dy.masa.litematica.materials.MaterialListSchematic materialList,
                                         Map<String, CloudStoreModels.Item> available, Map<String, Long> owned,
                                         boolean missingOnly, boolean fromCallback) {
        if (token != materialImportToken || token != materialImportPending) return;
        List<fi.dy.masa.litematica.materials.MaterialListEntry> entries = materialList.getMaterialsAll();
        // 还没统计完就别急着按空列表导入（回调回来时哪怕是空的也照常结算，避免状态卡在"正在统计"）
        if (entries.isEmpty() && !fromCallback) return;
        materialImportPending = 0;
        Map<String, Long> required = new LinkedHashMap<>();
        for (fi.dy.masa.litematica.materials.MaterialListEntry entry : entries) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty() || stack.isOf(Items.AIR) || isShulkerBox(Registries.ITEM.getId(stack.getItem()).toString()) || (stack.getItem() instanceof BlockItem block && block.getBlock() instanceof FluidBlock)) continue;
            required.merge(Registries.ITEM.getId(stack.getItem()).toString(), (long) entry.getCountTotal(), Long::sum);
        }
        applyRequirements(required, available, owned, missingOnly, 0);
        updateButtons();
    }

    // 仅导入容器中物品：统计原理图里容器方块实体（Items 列表）内装的物品作为需求，不含盒子本身和其他方块
    // missingOnly 时扣除本地背包（含盔甲、副手、潜影盒内）已有的数量
    void importSchematicContainers(java.nio.file.Path file, boolean missingOnly) {
        try {
            fi.dy.masa.litematica.schematic.LitematicaSchematic schematic = SchematicImportBrowser.load(file);
            if (schematic == null) { status = "无法读取原理图：" + file.getFileName(); return; }
            Map<String, CloudStoreModels.Item> available = new HashMap<>();
            for (CloudStoreModels.Item item : items) available.put(item.itemId, item);
            Map<String, Long> owned = missingOnly ? countLocalPlayerItems() : Map.of();
            Map<String, Long> required = new LinkedHashMap<>();
            for (String region : schematic.getAreas().keySet()) {
                var blockEntities = schematic.getBlockEntityMapForRegion(region);
                if (blockEntities == null) continue;
                for (fi.dy.masa.malilib.util.data.tag.CompoundData blockEntity : blockEntities.values()) collectContainerItems(blockEntity, required);
            }
            if (required.isEmpty()) { status = "原理图中没有容器物品"; return; }
            applyRequirements(required, available, owned, missingOnly, 0);
        } catch (RuntimeException error) { status = "原理图导入失败：" + message(error); }
        updateButtons();
    }

    // 需求匹配共用逻辑：匹配仓库库存，按 min(需求数, 库存) 填入 selected/selectedSources 并生成状态栏文本
    private void applyRequirements(Map<String, Long> required, Map<String, CloudStoreModels.Item> available, Map<String, Long> owned, boolean missingOnly, int skipped) {
        selected.clear(); selectedSources.clear(); long imported = 0; int importedTypes = 0, insufficient = 0, unmatched = 0;
        List<Text> issues = new ArrayList<>();
        for (Map.Entry<String, Long> requirement : required.entrySet()) {
            long need = requirement.getValue();
            if (missingOnly) {
                need -= owned.getOrDefault(requirement.getKey(), 0L);
                if (need <= 0) continue; // 背包已满足，无需从仓库取
            }
            CloudStoreModels.Item item = available.get(requirement.getKey());
            if (item == null || item.amount <= 0) { unmatched++; issues.add(Text.literal("§c仓库没有：" + itemDisplayName(requirement.getKey()) + "（需 " + need + "）")); continue; }
            long amount = Math.min(need, item.amount);
            if (amount < need) { insufficient++; issues.add(Text.literal("§6库存不足：" + item.displayName + "（需 " + need + "，仓库仅 " + item.amount + "）")); }
            if (amount > 0) {
                selected.put(item.itemKey, amount);
                selectedSources.put(item.itemKey, List.of(new SourceSelection(item.itemKey, item.displayName, false, 0, amount)));
                imported += amount; importedTypes++;
            }
        }
        status = "已导入 " + importedTypes + " 种材料，共 " + imported + " 件；" + insufficient + " 种库存不足；" + unmatched + " 种无法匹配" + (skipped > 0 ? "；已跳过 " + skipped + " 项" : "");
        if (!issues.isEmpty()) { issues.add(0, Text.literal("§e以下物品仓库无法满足：")); importIssueTooltip = issues; }
        else importIssueTooltip = List.of();
        importIssueStatus = status;
    }

    // 注册 ID 解析为本地化物品名，用于缺失明细展示；解析失败时原样返回 ID
    private String itemDisplayName(String id) {
        ItemStack stack = itemStack(id);
        return stack.isEmpty() ? id : stack.getName().getString();
    }

    // 容器方块实体数据的 Items 列表内物品计入需求（潜影盒、箱子、木桶、漏斗、发射器等）
    // litematica-DSH 分支的 getBlockEntityMapForRegion 返回 malilib CompoundData（官方 0.26.12 返回原版 NbtCompound）
    private void collectContainerItems(fi.dy.masa.malilib.util.data.tag.CompoundData blockEntity, Map<String, Long> required) {
        if (blockEntity == null) return;
        fi.dy.masa.malilib.util.data.tag.ListData list = blockEntity.getList("Items");
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof fi.dy.masa.malilib.util.data.tag.CompoundData entry)) continue;
            String id = entry.getStringOrDefault("id", "");
            if (id.isEmpty()) continue;
            int count = entry.getIntOrDefault("count", entry.getIntOrDefault("Count", 0));
            if (count > 0) required.merge(id, (long) count, Long::sum);
        }
    }

    // 统计本地玩家背包（含盔甲、副手、潜影盒内）各物品的数量，key 为物品注册 ID
    private Map<String, Long> countLocalPlayerItems() {
        Map<String, Long> counts = new HashMap<>();
        var player = MinecraftClient.getInstance().player;
        if (player == null) return counts;
        var inv = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) stacks.add(inv.getStack(i));
        collectItemCounts(stacks, counts);
        return counts;
    }

    private void collectItemCounts(List<ItemStack> stacks, Map<String, Long> counts) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), (long) stack.getCount(), Long::sum);
            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container != null) {
                List<ItemStack> inner = new ArrayList<>();
                container.streamNonEmpty().forEach(inner::add);
                collectItemCounts(inner, counts);
            }
        }
    }

    private void submitWithdraw() {
        if (submitting || selected.isEmpty() || me == null || me.user == null) return;
        String target = targetField.getText().trim();
        if (target.isEmpty()) target = me.user.username;
        if (!target.matches("[A-Za-z0-9_]{3,16}")) {
            status = "领取玩家 ID 必须是 3-16 位字母、数字或下划线";
            return;
        }
        Map<String, CloudStoreModels.Item> byKey = new HashMap<>();
        for (CloudStoreModels.Item item : items) byKey.put(item.itemKey, item);
        List<CloudStoreModels.WithdrawRequest> requests = new ArrayList<>();
        for (Map.Entry<String, List<SourceSelection>> entry : selectedSources.entrySet()) {
            CloudStoreModels.Item item = byKey.get(entry.getKey());
            String contentName = item == null ? entry.getKey() : item.displayName;
            for (SourceSelection source : entry.getValue()) {
                if (source.amount() <= 0) continue;
                if (!source.box()) {
                    requests.add(new CloudStoreModels.WithdrawRequest(source.sourceKey(), contentName, source.amount()));
                    continue;
                }
                long wholeBoxes = source.amount() / source.perBox();
                long remainder = source.amount() % source.perBox();
                if (wholeBoxes > 0) requests.add(new CloudStoreModels.WithdrawRequest(source.sourceKey(), source.label(), wholeBoxes));
                if (remainder > 0) requests.add(new CloudStoreModels.WithdrawRequest(entry.getKey(), contentName, remainder));
            }
        }
        if (requests.isEmpty()) return;
        submitting = true;
        status = "正在提交 " + requests.size() + " 种物品...";
        updateButtons();
        CloudStoreClient.api.withdrawMany(ownerUuid, requests, target).whenComplete((response, error) -> MinecraftClient.getInstance().execute(() -> {
            submitting = false;
            if (error != null) status = message(error);
            else {
                selected.clear();
                selectedSources.clear();
                status = response.message;
                CloudStoreClient.showStatus(response.message);
                // 取货后强制刷新，别被在飞的刷新挡掉
                loadInventoryOnly(false, true);
            }
            updateButtons();
        }));
    }

    private void clearSelection() {
        selected.clear();
        updateButtons();
    }

    private void updateButtons() {
        if (ownerButton != null) {
            String label = "选择仓库";
            if (me != null && me.owners != null && ownerIndex >= 0 && ownerIndex < me.owners.size()) {
                label = me.owners.get(ownerIndex).label;
            }
            ownerButton.setMessage(Text.literal(label));
            ownerButton.active = me != null && me.owners != null && me.owners.size() > 1 && !submitting;
        }
        if (refreshButton != null) refreshButton.active = !busy && inventoryInFlight == 0 && !submitting;
        if (depositButton != null) depositButton.active = !ownerUuid.isBlank() && !submitting;
        if (schematicButton != null) schematicButton.active = !submitting && !items.isEmpty();
        if (loginButton != null) loginButton.active = !busy && !submitting;
        if (submitButton != null) {
            submitButton.active = !selected.isEmpty() && !submitting;
            submitButton.setMessage(Text.literal(submitting ? "提交中..." : "提交取货"));
        }
        if (clearButton != null) clearButton.active = !selected.isEmpty() && !submitting;
    }

    private List<CloudStoreModels.Item> visibleItems() {
        String query = searchField == null ? savedSearch : searchField.getText();
        String normalized = normalizeSearch(query);
        Category category = categories.isEmpty() ? null : categories.get(Math.min(selectedCategory, categories.size() - 1));
        return items.stream()
            .filter(item -> normalized.isEmpty() || matchesSearch(item, normalized))
            .filter(item -> category == null || category.all
                || (category.favorites
                    ? CloudStoreClient.config.favorites.contains(item.itemKey)
                    : category.members.contains(itemStack(item.itemId).getItem())))
            .toList();
    }

    private void rebuildSearchIndex() {
        searchIndex.clear();
        for (CloudStoreModels.Item item : items) {
            String itemId = safe(item.itemId).toLowerCase();
            String shortId = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
            String names = safe(item.displayName) + " " + safe(item.baseDisplayName) + " " + safe(item.adminDisplayName)
                + " " + safe(item.personalAlias) + " " + String.join(" ", item.globalAliases) + " " + String.join(" ", item.personalAliases);
            searchIndex.put(item.itemKey, new SearchTerms(
                normalizeSearch(names),
                normalizeSearch(itemId),
                normalizeSearch(shortId),
                normalizeSearch(safe(item.shortCode)),
                normalizeSearch(toPinyin(names, false)),
                normalizeSearch(toPinyin(names, true))
            ));
        }
    }

    private String toPinyin(String text, boolean initialsOnly) {
        StringBuilder result = new StringBuilder();
        for (char character : safe(text).toCharArray()) {
            String[] values = PinyinHelper.toHanyuPinyinStringArray(character);
            if (values != null && values.length > 0) {
                String syllable = values[0].replaceAll("[^a-zA-Z]", "").toLowerCase();
                if (!syllable.isEmpty()) result.append(initialsOnly ? syllable.charAt(0) : syllable);
            } else if (Character.isLetterOrDigit(character)) {
                result.append(Character.toLowerCase(character));
            }
        }
        return result.toString();
    }

    private String normalizeSearch(String value) {
        return safe(value).toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff]", "");
    }

    private boolean matchesSearch(CloudStoreModels.Item item, String query) {
        SearchTerms terms = searchIndex.get(item.itemKey);
        return terms != null && terms.matches(query);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xF0101116);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFFFF);
        if (me != null && me.user != null) context.drawTextWithShadow(this.textRenderer, Text.literal(me.user.username), 10, 10, 0xFF9AA5B1);
        if (lastInventory != null && lastInventory.stats != null && lastInventory.quota != null) {
            String inventorySummary = "库存 " + compact(lastInventory.stats.totalAmount) + " / " + lastInventory.stats.itemTypes
                + " 种  容量 " + lastInventory.quota.usedSlots + "/" + lastInventory.quota.quotaSlots + " (" + String.format(java.util.Locale.ROOT, "%.1f", lastInventory.quota.usagePercent) + "%)";
            context.drawTextWithShadow(this.textRenderer, Text.literal(inventorySummary), Math.max(10, this.width - this.textRenderer.getWidth(inventorySummary) - 10), 10, 0xFF9AA5B1);
        }

        int tabColumns = Math.max(1, (this.width - 20) / TAB_STEP);
        int tabRows = Math.max(1, (int) Math.ceil((double) Math.max(1, categories.size()) / tabColumns));
        Text tooltip = null;
        for (int i = 0; i < categories.size(); i++) {
            int x = 10 + (i % tabColumns) * TAB_STEP;
            int y = tabY + (i / tabColumns) * TAB_STEP;
            boolean active = i == selectedCategory;
            boolean hovered = mouseX >= x && mouseX < x + TAB_SIZE && mouseY >= y && mouseY < y + TAB_SIZE;
            context.fill(x, y, x + TAB_SIZE, y + TAB_SIZE, active ? 0xFF356A52 : hovered ? 0xFF354252 : 0xFF242A33);
            ItemStack icon = categories.get(i).icon;
            if (icon != null && !icon.isEmpty()) context.drawItem(icon, x + 4, y + 4);
            if (hovered) tooltip = categories.get(i).label;
        }

        gridY = tabY + tabRows * TAB_STEP + 15;
        gridBottom = this.height - (this.width < 390 ? 77 : 51);
        List<CloudStoreModels.Item> visible = visibleItems();
        int columns = Math.max(1, (this.width - 20) / TILE_STEP);
        int rows = Math.max(1, (gridBottom - gridY) / TILE_STEP);
        int maxScroll = Math.max(0, (int) Math.ceil((double) visible.size() / columns) - rows);
        scroll = Math.min(scroll, maxScroll);
        // 平滑滚动：视觉位置逐帧逼近目标行（参考 Smooth Scrolling）
        scrollAnim += (scroll - scrollAnim) * Math.min(1.0f, Math.max(0.0f, delta) * 0.45f);
        if (Math.abs(scroll - scrollAnim) < 0.002f) scrollAnim = scroll;
        if (scrollAnim < 0) scrollAnim = 0;
        if (scrollAnim > maxScroll) scrollAnim = maxScroll;
        int animBaseRow = (int) Math.floor(scrollAnim);
        float animFrac = scrollAnim - animBaseRow;
        int rowOffset = Math.round(-animFrac * TILE_STEP);
        int start = animBaseRow * columns;
        int x0 = Math.max(10, (this.width - columns * TILE_STEP) / 2);
        context.enableScissor(Math.max(0, x0 - 4), Math.max(0, gridY - 4),
            Math.min(this.width, x0 + columns * TILE_STEP + 4), Math.min(this.height, gridBottom + 4));
        for (int i = 0; i < (rows + 1) * columns && start + i < visible.size(); i++) {
            CloudStoreModels.Item item = visible.get(start + i);
            int x = x0 + (i % columns) * TILE_STEP;
            int y = gridY + (i / columns) * TILE_STEP + rowOffset;
            if (y + TILE_SIZE <= gridY || y >= gridBottom) continue;
            boolean hovered = mouseX >= x && mouseX < x + TILE_SIZE && mouseY >= y && mouseY < y + TILE_SIZE;
            long selectedAmount = selected.getOrDefault(item.itemKey, 0L);
            int background = selectedAmount > 0 ? 0xFF315C49 : hovered ? 0xFF354252 : 0xFF242A33;
            context.fill(x, y, x + TILE_SIZE, y + TILE_SIZE, background);
            if (selectedAmount > 0) {
                context.fill(x, y, x + TILE_SIZE, y + 2, 0xFF69D69A);
                context.fill(x, y + TILE_SIZE - 2, x + TILE_SIZE, y + TILE_SIZE, 0xFF69D69A);
                context.fill(x, y, x + 2, y + TILE_SIZE, 0xFF69D69A);
                context.fill(x + TILE_SIZE - 2, y, x + TILE_SIZE, y + TILE_SIZE, 0xFF69D69A);
            }
            ItemStack stack = itemStack(item);
            if (!stack.isEmpty()) {
                // 悬停缩放平滑过渡：当前值逐帧逼近目标值
                float targetScale = hovered ? HOVER_ITEM_SCALE : ITEM_SCALE;
                float scale = hoverScales.getOrDefault(item.itemKey, ITEM_SCALE);
                scale += (targetScale - scale) * Math.min(1.0f, Math.max(0.0f, delta) * 12.0f);
                if (Math.abs(scale - targetScale) < 0.01f) scale = targetScale;
                hoverScales.put(item.itemKey, scale);
                var matrices = context.getMatrices();
                matrices.pushMatrix();
                matrices.translate(x + TILE_SIZE / 2.0f, y + TILE_SIZE / 2.0f);
                matrices.scale(scale, scale);
                matrices.translate(-8.0f, -8.0f);
                context.drawItem(stack, 0, 0);
                matrices.popMatrix();
            }
            String amount = compact(item.amount);
            if (!stack.isEmpty()) context.drawStackOverlay(this.textRenderer, stack, x + 17, y + 17, amount);
            if (selectedAmount > 0) {
                String selectedText = compact(selectedAmount);
                context.drawTextWithShadow(this.textRenderer, selectedText, x + 1, y, 0xFF9FFFC5);
            }
            if (hovered) {
                tooltip = Text.literal(item.displayName + "  可取 " + item.amount + availabilitySuffix(item));
            }
        }
        context.disableScissor();

        if (visible.isEmpty() && status.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("此分类没有匹配物品"), this.width / 2, (gridY + gridBottom) / 2, 0xFFAAB4C0);
        }

        int bottomY = this.height - 27;
        boolean twoRowBottom = this.width < 390;
        context.drawTextWithShadow(this.textRenderer, Text.literal("领取玩家"), 10, targetField.getY() - 11, 0xFFC5CED8);
        long totalSelected = selected.values().stream().mapToLong(Long::longValue).sum();
        String summary = "已选 " + selected.size() + " 种 / " + totalSelected + " 件";
        int summaryX = targetField.getX() + targetField.getWidth() + 8;
        int maxSummaryWidth = Math.max(0, clearButton.getX() - summaryX - 8);
        if (!twoRowBottom && maxSummaryWidth > 40) {
            context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(summary, maxSummaryWidth), summaryX, bottomY + 6, 0xFFD4D9E0);
        }
        String displayStatus = !status.isEmpty() ? status : activeStatus();
        List<Text> statusTooltip = null;
        if (!displayStatus.isEmpty()) {
            displayStatus = this.textRenderer.trimToWidth(displayStatus, Math.max(80, this.width - 20));
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(displayStatus), this.width / 2, gridY - 11, statusColor);
            // 状态栏为原理图导入结果且存在仓库缺失时，悬停展开缺失明细
            if (!importIssueTooltip.isEmpty() && status.equals(importIssueStatus)) {
                int statusWidth = this.textRenderer.getWidth(displayStatus);
                if (mouseX >= this.width / 2 - statusWidth / 2 && mouseX < this.width / 2 + statusWidth / 2 && mouseY >= gridY - 13 && mouseY < gridY - 1) statusTooltip = importIssueTooltip;
            }
        }

        super.render(context, mouseX, mouseY, delta);
        renderTargetSuggestions(context, mouseX, mouseY);
        if (statusTooltip != null) context.drawTooltip(this.textRenderer, statusTooltip, mouseX, mouseY);
        else if (tooltip != null) context.drawTooltip(this.textRenderer, tooltip, mouseX, mouseY);
    }

    private String availabilitySuffix(CloudStoreModels.Item item) {
        if (item.boxedAmount <= 0) return "（散装）";
        if (item.looseAmount <= 0) return "（盒内）";
        return "（散装 " + item.looseAmount + " + 盒内 " + item.boxedAmount + "）";
    }

    private String activeStatus() {
        if (lastInventory == null || lastInventory.active == null) return "";
        CloudStoreModels.Active active = lastInventory.active;
        if (!safe(active.message).isBlank()) return active.message;
        if (!safe(active.phase).isBlank()) return "仓库操作中：" + active.phase;
        return "";
    }

    // sourceKey = 库存行原始 itemKey（服务端登记的 key），整盒取货请求必须用它而不是盒子的 itemId
    record BoxGroup(String label, String itemId, String sourceKey, int boxCount, long perBoxAmount, List<CloudStoreModels.Item> contents) {
    }

    record SourceSelection(String sourceKey, String label, boolean box, long perBox, long amount) {
    }

    java.util.List<SourceSelection> getSelectedSources(CloudStoreModels.Item item) {
        return selectedSources.getOrDefault(item.itemKey, List.of());
    }

    void setSelectedSources(CloudStoreModels.Item item, java.util.List<SourceSelection> sources) {
        long total = 0;
        for (SourceSelection source : sources) total += source.amount();
        if (total <= 0) {
            selectedSources.remove(item.itemKey);
            selected.remove(item.itemKey);
        } else {
            selectedSources.put(item.itemKey, List.copyOf(sources));
            selected.put(item.itemKey, total);
        }
        updateButtons();
    }

    java.util.List<BoxGroup> boxGroupsFor(CloudStoreModels.Item item) {
        List<CloudStoreModels.Item> rows = lastInventory == null || lastInventory.items == null
            ? List.<CloudStoreModels.Item>of() : lastInventory.items;
        Map<String, BoxGroup> groups = new LinkedHashMap<>();
        for (CloudStoreModels.Item row : rows) {
            if (!isShulkerBox(row.itemId) || isEmptyShulkerBox(row) || row.shulkerContents == null) continue;
            long perBox = 0;
            boolean matched = false;
            StringBuilder signature = new StringBuilder();
            for (CloudStoreModels.Item content : row.shulkerContents) {
                if (content == null || content.amount <= 0) continue;
                String key = variantKey(content);
                if (key.equals(item.itemKey)) {
                    perBox += content.amount;
                    matched = true;
                }
                signature.append(key).append('x').append(content.amount).append(';');
            }
            if (!matched) continue;
            int add = (int) Math.min(Integer.MAX_VALUE, Math.max(1, row.amount));
            String key = row.itemId + "|" + signature;
            String sourceKey = variantKey(row);
            BoxGroup existing = groups.get(key);
            String label = safe(row.displayName).isBlank() ? "潜影盒" : row.displayName;
            groups.put(key, existing == null
                ? new BoxGroup(label, row.itemId, sourceKey, add, perBox, row.shulkerContents)
                : new BoxGroup(existing.label, existing.itemId, existing.sourceKey, existing.boxCount + add, perBox, existing.contents));
        }
        return List.copyOf(groups.values());
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (click.button() == 0 && clickTargetSuggestion(mouseX, mouseY)) return true;
        if (click.button() == 0 && mouseX >= targetField.getX() && mouseX < targetField.getX() + targetField.getWidth()
            && mouseY >= targetField.getY() && mouseY < targetField.getY() + targetField.getHeight()) {
            refreshTargetSuggestions();
            targetSuggestionsOpen = !targetSuggestions.isEmpty();
        }
        if (click.button() == 0) {
            int tabColumns = Math.max(1, (this.width - 20) / TAB_STEP);
            if (mouseY >= tabY && mouseY < gridY && mouseX >= 10) {
                int column = (int) ((mouseX - 10) / TAB_STEP);
                int row = (int) ((mouseY - tabY) / TAB_STEP);
                int localX = (int) (mouseX - 10) % TAB_STEP;
                int localY = (int) (mouseY - tabY) % TAB_STEP;
                int index = row * tabColumns + column;
                if (localX < TAB_SIZE && localY < TAB_SIZE && index >= 0 && index < categories.size()) {
                    selectedCategory = index;
                    scroll = 0;
                    return true;
                }
            }
            if (mouseY >= gridY && mouseY < gridBottom) {
                List<CloudStoreModels.Item> visible = visibleItems();
                int columns = Math.max(1, (this.width - 20) / TILE_STEP);
                int x0 = Math.max(10, (this.width - columns * TILE_STEP) / 2);
                if (mouseX >= x0) {
                    int localX = (int) (mouseX - x0);
                    // 与绘制一致：使用平滑滚动的视觉位置做命中判定
                    int animBaseRow = (int) Math.floor(scrollAnim);
                    int rowOffset = Math.round(-(scrollAnim - animBaseRow) * TILE_STEP);
                    int relY = (int) (mouseY - gridY) - rowOffset;
                    int row = relY / TILE_STEP;
                    int localY = relY % TILE_STEP;
                    int column = localX / TILE_STEP;
                    int index = animBaseRow * columns + row * columns + column;
                    if (localX % TILE_STEP < TILE_SIZE && localY < TILE_SIZE && index >= 0 && index < visible.size()) {
                        CloudStoreModels.Item item = visible.get(index);
                        this.client.setScreen(new WithdrawScreen(this, item, selected.getOrDefault(item.itemKey, 0L)));
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (targetSuggestionsOpen && !targetSuggestions.isEmpty()) {
            int rows = Math.min(6, targetSuggestions.size() - targetSuggestionScroll);
            int y = targetField.getY() - rows * 18 - 2;
            if (mouseX >= targetField.getX() && mouseX < targetField.getX() + targetField.getWidth()
                && mouseY >= y && mouseY < targetField.getY()) {
                int maxScroll = Math.max(0, targetSuggestions.size() - 6);
                targetSuggestionScroll = Math.max(0, Math.min(maxScroll, targetSuggestionScroll - (int) Math.signum(verticalAmount)));
                targetSuggestionIndex = Math.max(targetSuggestionScroll, Math.min(targetSuggestions.size() - 1, targetSuggestionScroll + rows - 1));
                return true;
            }
        }
        if (mouseY < gridY || mouseY >= gridBottom) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int columns = Math.max(1, (this.width - 20) / TILE_STEP);
        int rows = Math.max(1, (gridBottom - gridY) / TILE_STEP);
        int maxScroll = Math.max(0, (int) Math.ceil((double) visibleItems().size() / columns) - rows);
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(verticalAmount)));
        return true;
    }

    @Override
    public void close() {
        loadInventoryOnly(false, true);
        if (this.client != null) this.client.setScreen(parent);
    }

    private ItemStack itemStack(String id) {
        try {
            Identifier identifier = Identifier.tryParse(id);
            return identifier == null ? ItemStack.EMPTY : new ItemStack(Registries.ITEM.get(identifier));
        } catch (RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }

    // 供子界面复用：带变体数据（如烟花等级）的图标
    // 注意：不能用反射设置组件——反射的字符串类名不会参与构建重映射，运行时会找不到类
    ItemStack itemStack(CloudStoreModels.Item item) {
        ItemStack stack = itemStack(item.itemId);
        if (stack.isEmpty() || !"minecraft:firework_rocket".equals(item.itemId)) return stack;
        int flight = parseFlightDuration(item);
        if (flight >= 0) stack.set(DataComponentTypes.FIREWORKS, new FireworksComponent(flight, List.of()));
        return stack;
    }

    private int parseFlightDuration(CloudStoreModels.Item item) {
        for (String json : new String[]{safe(item.metaJson), safe(item.nbtJson)}) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"(?:flightDuration|flight_duration|Flight)\"\\s*:\\s*(\\d+)")
                .matcher(json);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (RuntimeException ignored) {
                }
            }
        }
        return -1;
    }

    private String compact(long amount) {
        if (amount >= 1_000_000) return formatDecimal(amount, 1_000_000, "m");
        if (amount >= 10_000) return formatDecimal(amount, 1_000, "k");
        return String.valueOf(amount);
    }

    private String formatDecimal(long amount, long divisor, String suffix) {
        long whole = amount / divisor;
        if (whole >= 100) return whole + suffix;
        long decimal = (amount % divisor) * 10 / divisor;
        return decimal == 0 ? whole + suffix : whole + "." + decimal + suffix;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String message(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "云仓库请求失败" : current.getMessage();
    }

    private record Category(Text label, ItemStack icon, Set<Item> members, boolean all, boolean favorites) {
    }

    private record LoadResult(CloudStoreModels.MeResponse me, String ownerUuid, CloudStoreModels.InventoryResponse inventory) {
    }

    private record SearchTerms(String names, String fullId, String shortId, String shortCode, String pinyin, String initials) {
        boolean matches(String query) {
            return names.contains(query)
                || fullId.contains(query)
                || shortId.contains(query)
                || shortCode.contains(query)
                || pinyin.contains(query)
                || initials.contains(query);
        }
    }
}
