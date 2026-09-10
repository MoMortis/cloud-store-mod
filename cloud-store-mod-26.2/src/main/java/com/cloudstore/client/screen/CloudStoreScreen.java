package com.cloudstore.client.screen;

import com.cloudstore.client.CloudStoreClient;
import com.cloudstore.client.api.CloudStoreModels;
import com.mojang.blaze3d.platform.InputConstants;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

public final class CloudStoreScreen extends Screen {
    private static final int TILE_SIZE = 34;
    private static final int TILE_STEP = 34;
    private static final float ITEM_SCALE = 1.25f;
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
    private int gridY;
    private int gridBottom;
    private int tabY = 55;
    private boolean initialized;
    private boolean busy;
    private boolean submitting;
    private String status = "";
    private int statusColor = 0xF1B36B;
    private long refreshStartedAt;
    private String savedSearch = "";
    private String savedTarget = "";
    private List<String> targetSuggestions = List.of();
    private int targetSuggestionIndex;
    private int targetSuggestionScroll;
    private boolean targetSuggestionsOpen;
    private EditBox searchField;
    private EditBox targetField;
    private Button ownerButton;
    private Button schematicButton;
    private Button depositButton;
    private Button loginButton;
    private Button refreshButton;
    private Button submitButton;
    private Button clearButton;

    public CloudStoreScreen(Screen parent) {
        super(Component.literal("云仓库"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (searchField != null) savedSearch = searchField.getValue();
        if (targetField != null) savedTarget = targetField.getValue();

        boolean narrowToolbar = this.width < 500;
        int searchWidth = narrowToolbar ? Math.max(80, this.width - 116) : Math.min(180, Math.max(100, this.width / 4));
        searchField = new EditBox(this.font, 10, 28, searchWidth, 20, Component.literal("搜索物品"));
        searchField.setHint(Component.literal("搜索名称 / ID / 短码"));
        searchField.setMaxLength(80);
        searchField.setValue(savedSearch);
        searchField.setResponder(value -> {
            savedSearch = value;
            scroll = 0;
        });
        this.addRenderableWidget(searchField);

        int ownerX = narrowToolbar ? 10 : 16 + searchWidth;
        int rightToolbarWidth = 106;
        int schematicWidth = 76;
        int ownerWidth = narrowToolbar
            ? Math.max(70, this.width - ownerX - schematicWidth - 12)
            : Math.max(70, Math.min(130, this.width - ownerX - rightToolbarWidth - schematicWidth - 16));
        int toolbarY = narrowToolbar ? 52 : 28;
        ownerButton = Button.builder(Component.literal("选择仓库"), button -> cycleOwner())
            .bounds(ownerX, toolbarY, ownerWidth, 20).build();
        this.addRenderableWidget(ownerButton);
        schematicButton = Button.builder(Component.literal("导入原理图"), button -> openSchematicBrowser())
            .bounds(ownerX + ownerWidth + 8, toolbarY, schematicWidth, 20).build();
        this.addRenderableWidget(schematicButton);
        refreshButton = Button.builder(Component.literal("刷新"), button -> reload())
            .bounds(this.width - 106, 28, 48, 20).build();
        this.addRenderableWidget(refreshButton);
        depositButton = Button.builder(Component.literal("存入"), button -> deposit())
            .bounds(this.width - 52, 28, 42, 20).build();
        this.addRenderableWidget(depositButton);
        tabY = narrowToolbar ? 79 : 55;

        boolean twoRowBottom = this.width < 390;
        int bottomY = this.height - 27;
        int actionWidth = this.width < 430 ? 78 : 96;
        int targetY = twoRowBottom ? bottomY - 26 : bottomY;
        int targetWidth = twoRowBottom ? this.width - 20 : Math.min(150, Math.max(86, this.width - actionWidth - 170));
        targetField = new EditBox(this.font, 10, targetY, targetWidth, 20, Component.literal("领取玩家"));
        targetField.setHint(Component.literal("留空=自己；可填代取 ID"));
        targetField.setMaxLength(16);
        targetField.setTextColor(0xFFFFFFFF);
        targetField.setValue(savedTarget);
        targetField.setResponder(value -> {
            savedTarget = value;
            refreshTargetSuggestions();
        });
        this.addRenderableWidget(targetField);

        int closeX = this.width - 44;
        int settingsX = closeX - 52;
        int compactActionWidth = twoRowBottom ? 66 : actionWidth;
        int submitX = settingsX - compactActionWidth - 6;
        int clearX = twoRowBottom ? submitX - 48 - 6 : submitX - 48 - 6;
        clearButton = Button.builder(Component.literal("清空"), button -> clearSelection())
            .bounds(clearX, bottomY, 48, 20).build();
        this.addRenderableWidget(clearButton);
        submitButton = Button.builder(Component.literal("提交取货"), button -> submitWithdraw())
            .bounds(submitX, bottomY, compactActionWidth, 20).build();
        this.addRenderableWidget(submitButton);
        loginButton = Button.builder(Component.literal("登录"), button -> startLogin())
            .bounds(settingsX, bottomY, 46, 20).build();
        this.addRenderableWidget(loginButton);
        this.addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
            .bounds(closeX, bottomY, 34, 20).build());

        if (!initialized) {
            initialized = true;
            restoreCachedData();
        } else {
            updateButtons();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (targetField != null && targetField.isFocused() && event.key() == 258) {
            refreshTargetSuggestions();
            if (!targetSuggestions.isEmpty()) {
                completeTarget(event.modifiers() > 0);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void refreshTargetSuggestions() {
        if (targetField == null || minecraft == null) {
            targetSuggestions = List.of();
            targetSuggestionIndex = 0;
            targetSuggestionScroll = 0;
            targetSuggestionsOpen = false;
            return;
        }
        String prefix = targetField.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        List<String> matches = new ArrayList<>();
        if (minecraft.getConnection() != null) minecraft.getConnection().getOnlinePlayers().stream()
            .map(info -> profileName(info.getProfile()))
            .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
            .forEach(name -> {
                if (!matches.contains(name)) matches.add(name);
            });
        if (minecraft.player != null) {
            String self = profileName(minecraft.player.getGameProfile());
            if (self.toLowerCase(java.util.Locale.ROOT).startsWith(prefix) && !matches.contains(self)) matches.add(self);
        }
        matches.sort(String.CASE_INSENSITIVE_ORDER);
        targetSuggestions = List.copyOf(matches);
        targetSuggestionIndex = Math.min(targetSuggestionIndex, Math.max(0, targetSuggestions.size() - 1));
        targetSuggestionScroll = Math.min(targetSuggestionScroll, Math.max(0, targetSuggestions.size() - 6));
        targetSuggestionsOpen = targetField.isFocused() && !targetSuggestions.isEmpty();
    }

    private String profileName(Object profile) {
        if (profile == null) return "";
        try {
            return (String) profile.getClass().getMethod("name").invoke(profile);
        } catch (ReflectiveOperationException ignored) {
            try {
                return (String) profile.getClass().getMethod("getName").invoke(profile);
            } catch (ReflectiveOperationException ignoredAgain) {
                return "";
            }
        }
    }

    private void completeTarget(boolean reverse) {
        targetSuggestionIndex = Math.floorMod(targetSuggestionIndex + (reverse ? -1 : 1), targetSuggestions.size());
        keepTargetSuggestionVisible();
        targetField.setValue(targetSuggestions.get(targetSuggestionIndex));
        targetField.setCursorPosition(targetField.getValue().length());
        targetSuggestionsOpen = true;
    }

    private void keepTargetSuggestionVisible() {
        if (targetSuggestionIndex < targetSuggestionScroll) targetSuggestionScroll = targetSuggestionIndex;
        if (targetSuggestionIndex >= targetSuggestionScroll + 6) targetSuggestionScroll = targetSuggestionIndex - 5;
        targetSuggestionScroll = Math.max(0, Math.min(targetSuggestionScroll, Math.max(0, targetSuggestions.size() - 6)));
    }

    private void renderTargetSuggestions(GuiGraphicsExtractor context, int mouseX, int mouseY) {
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
            context.text(font, Component.literal(targetSuggestions.get(index)), x + 5, rowY + 5, 0xFFFFFFFF, true);
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
        targetField.setValue(targetSuggestions.get(index));
        targetField.setFocused(true);
        setFocused((GuiEventListener) targetField);
        targetField.setCursorPosition(targetField.getValue().length());
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
        loadInventoryOnly(true);
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
        if (cachedInventory == null) {
            loadInventoryOnly();
            return;
        }
        applyInventory(cachedInventory);
        status = "已显示缓存，关闭界面时自动刷新";
        updateButtons();
    }

    private void load() {
        if (busy) return;
        busy = true;
        if (items.isEmpty()) status = "正在连接云仓库...";
        else status = "正在后台刷新...";
        updateButtons();
        String preferredOwner = ownerUuid;
        CloudStoreClient.api.connect().thenCompose(response -> {
            CloudStoreClient.cache.putMe(endpoint(), response);
            String resolvedOwner = resolveOwner(response, preferredOwner);
            return CloudStoreClient.api.inventory(resolvedOwner)
                .thenApply(inventory -> new LoadResult(response, resolvedOwner, inventory));
        }).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (error != null) {
                status = message(error);
                updateButtons();
                return;
            }
            me = result.me;
            ownerUuid = result.ownerUuid;
            CloudStoreClient.cache.putInventory(endpoint(), ownerUuid, result.inventory);
            applyInventory(result.inventory);
            status = "";
            updateButtons();
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
        loadInventoryOnly();
    }

    private void loadInventoryOnly() {
        loadInventoryOnly(false);
    }

    private void loadInventoryOnly(boolean showRefreshComplete) {
        loadInventoryOnly(showRefreshComplete, false);
    }

    private void loadInventoryOnly(boolean showRefreshComplete, boolean force) {
        if ((busy && !force) || ownerUuid.isBlank()) return;
        busy = true;
        refreshStartedAt = showRefreshComplete ? System.nanoTime() : 0L;
        status = items.isEmpty() ? "正在读取库存..." : "已显示缓存，正在后台刷新...";
        statusColor = 0xF1B36B;
        updateButtons();
        String requestedOwner = ownerUuid;
        CloudStoreClient.api.inventory(requestedOwner).whenComplete((response, error) -> {
            if (error == null) CloudStoreClient.cache.putInventory(endpoint(), requestedOwner, response);
            Minecraft.getInstance().execute(() -> {
                busy = false;
                if (error != null) {
                    status = message(error);
                    statusColor = 0xF1B36B;
                }
                else if (requestedOwner.equals(ownerUuid)) {
                    applyInventory(response);
                    status = "";
                    statusColor = 0xFF00FF00;
                    if (showRefreshComplete) {
                        CloudStoreClient.showStatus("刷新完成（%.2f 秒）".formatted((System.nanoTime() - refreshStartedAt) / 1_000_000_000.0d));
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
        return item.shulkerContents == null || item.shulkerContents.stream()
            .noneMatch(content -> content != null && content.amount > 0);
    }

    private void openSchematicBrowser() {
        if (submitting || items.isEmpty()) return;
        new SchematicImportBrowser(this).open();
    }

    void importSchematic(java.nio.file.Path file) {
        try {
            fi.dy.masa.litematica.schematic.LitematicaSchematic schematic = SchematicImportBrowser.load(file);
            if (schematic == null) {
                status = "无法读取原理图：" + file.getFileName();
                return;
            }

            Map<String, CloudStoreModels.Item> available = new HashMap<>();
            for (CloudStoreModels.Item item : items) available.put(item.itemId, item);

            Map<String, Long> required = new LinkedHashMap<>();
            int skipped = 0;
            for (fi.dy.masa.litematica.materials.MaterialListEntry entry
                : new fi.dy.masa.litematica.materials.MaterialListSchematic(schematic, true).getMaterialsAll()) {
                ItemStack stack = entry.getStack();
                if (skipSchematicStack(stack)) {
                    skipped++;
                    continue;
                }
                Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id != null) required.merge(id.toString(), (long) entry.getCountTotal(), Long::sum);
            }

            selected.clear();
            selectedSources.clear();
            long imported = 0;
            int importedTypes = 0;
            int insufficientTypes = 0;
            int unmatchedTypes = 0;
            for (Map.Entry<String, Long> requirement : required.entrySet()) {
                CloudStoreModels.Item item = available.get(requirement.getKey());
                if (item == null || item.amount <= 0) {
                    unmatchedTypes++;
                    continue;
                }
                long amount = Math.min(requirement.getValue(), item.amount);
                if (amount < requirement.getValue()) insufficientTypes++;
                if (amount > 0) {
                    selected.put(item.itemKey, amount);
                    selectedSources.put(item.itemKey, List.of(
                        new SourceSelection(item.itemKey, item.displayName, false, 0, amount)));
                    imported += amount;
                    importedTypes++;
                }
            }
            status = "已导入 " + importedTypes + " 种材料，共 " + imported + " 件；"
                + insufficientTypes + " 种库存不足；" + unmatchedTypes + " 种无法匹配"
                + (skipped > 0 ? "；已跳过 " + skipped + " 项" : "");
        } catch (RuntimeException error) {
            CloudStoreClient.LOGGER.warn("Unable to import schematic {}", file, error);
            status = "原理图导入失败：" + message(error);
        }
        updateButtons();
    }

    private boolean skipSchematicStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.is(Items.AIR) || isShulkerBox(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) return true;
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof LiquidBlock;
    }

    private void rebuildSearchIndex() {
        searchIndex.clear();
        for (CloudStoreModels.Item item : items) {
            String itemId = safe(item.itemId).toLowerCase();
            String idWithoutNamespace = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
            String names = safe(item.displayName) + " " + safe(item.baseDisplayName) + " " + safe(item.adminDisplayName)
                + " " + safe(item.personalAlias) + " " + String.join(" ", item.globalAliases) + " " + String.join(" ", item.personalAliases);
            String pinyin = toPinyin(names, false);
            String initials = toPinyin(names, true);
            searchIndex.put(item.itemKey, new SearchTerms(
                normalizeSearch(names),
                normalizeSearch(itemId),
                normalizeSearch(idWithoutNamespace),
                normalizeSearch(safe(item.shortCode)),
                normalizeSearch(pinyin),
                normalizeSearch(initials)
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

    private void rebuildCategories() {
        categories.clear();
        categories.add(new Category(Component.literal("全部"), new ItemStack(Items.CHEST), Set.of(), true));
        try {
            if (this.minecraft != null && this.minecraft.level != null && this.minecraft.getConnection() != null) {
                CreativeModeTabs.tryRebuildTabContents(
                    this.minecraft.getConnection().enabledFeatures(),
                    false,
                    this.minecraft.level.registryAccess()
                );
            }
            for (CreativeModeTab group : CreativeModeTabs.tabs()) {
                try {
                    Set<Item> members = new HashSet<>();
                    for (ItemStack stack : group.getDisplayItems()) members.add(stack.getItem());
                    categories.add(new Category(group.getDisplayName(), group.getIconItem(), members, false));
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
        CloudStoreClient.api.deposit(ownerUuid).whenComplete((response, error) -> Minecraft.getInstance().execute(() -> {
            submitting = false;
            if (error != null) status = message(error);
            else {
                status = response.message;
                CloudStoreClient.showStatus(response.message);
                loadInventoryOnly();
            }
            updateButtons();
        }));
    }

    private void startLogin() {
        if (busy || submitting) return;
        busy = true;
        status = "正在重新登录...";
        updateButtons();
        CloudStoreClient.api.logout().handle((response, error) -> {
            CloudStoreClient.api.clearSession();
            return null;
        }).thenCompose(ignored -> CloudStoreClient.api.startLoginChallenge()).whenComplete((challenge, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) {
                busy = false;
                status = message(error);
                updateButtons();
                return;
            }
            if (challenge.loggedIn || "approved".equals(challenge.status)) {
                busy = false;
                status = "网页登录已恢复。";
                load();
                return;
            }
            if (challenge.command == null || challenge.command.isBlank()) {
                busy = false;
                status = "服务器未返回登录指令。";
                updateButtons();
                return;
            }
            if (!CloudStoreClient.sendChatCommand(challenge.command)) {
                busy = false;
                updateButtons();
                return;
            }
            status = "已发送登录私聊，正在等待验证...";
            CloudStoreClient.showStatus(status);
            pollLoginChallenge();
        }));
    }

    private void pollLoginChallenge() {
        CloudStoreClient.api.loginChallengeStatus().whenComplete((challenge, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) {
                busy = false;
                status = message(error);
                updateButtons();
                return;
            }
            if (challenge.loggedIn || "approved".equals(challenge.status)) {
                busy = false;
                status = "登录成功，正在读取库存...";
                CloudStoreClient.showStatus("登录成功");
                load();
                return;
            }
            if ("expired".equals(challenge.status) || "cancelled".equals(challenge.status) || "none".equals(challenge.status)) {
                busy = false;
                status = "验证码已失效，请重新登录。";
                updateButtons();
                return;
            }
            java.util.concurrent.CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS)
                .execute(() -> pollLoginChallenge());
        }));
    }

    private void submitWithdraw() {
        if (submitting || selected.isEmpty() || me == null || me.user == null) return;
        String target = targetField.getValue().trim();
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
        CloudStoreClient.api.withdrawMany(ownerUuid, requests, target).whenComplete((response, error) -> Minecraft.getInstance().execute(() -> {
            submitting = false;
            if (error != null) status = message(error);
            else {
                selected.clear();
                selectedSources.clear();
                status = response.message;
                CloudStoreClient.showStatus(response.message);
                loadInventoryOnly();
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
            ownerButton.setMessage(Component.literal(label));
            ownerButton.active = me != null && me.owners != null && me.owners.size() > 1 && !submitting;
        }
        if (refreshButton != null) refreshButton.active = !busy && !submitting;
        if (depositButton != null) depositButton.active = !ownerUuid.isBlank() && !submitting;
        if (schematicButton != null) schematicButton.active = !submitting && !items.isEmpty();
        if (loginButton != null) loginButton.active = !busy && !submitting;
        if (submitButton != null) {
            submitButton.active = !selected.isEmpty() && !submitting;
            submitButton.setMessage(Component.literal(submitting ? "提交中..." : "提交取货"));
        }
        if (clearButton != null) clearButton.active = !selected.isEmpty() && !submitting;
    }

    private List<CloudStoreModels.Item> visibleItems() {
        String query = searchField == null ? savedSearch : searchField.getValue();
        String normalized = normalizeSearch(query);
        Category category = categories.isEmpty() ? null : categories.get(Math.min(selectedCategory, categories.size() - 1));
        return items.stream()
            .filter(item -> normalized.isEmpty() || matchesSearch(item, normalized))
            .filter(item -> category == null || category.all || category.members.contains(itemStack(item.itemId).getItem()))
            .toList();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xF0101116);
        context.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        if (me != null && me.user != null) context.text(this.font, Component.literal(me.user.username), 10, 10, 0x9AA5B1, true);
        if (lastInventory != null && lastInventory.stats != null && lastInventory.quota != null) {
            String inventorySummary = "库存 " + compact(lastInventory.stats.totalAmount) + " / " + lastInventory.stats.itemTypes
                + " 种  容量 " + lastInventory.quota.usedSlots + "/" + lastInventory.quota.quotaSlots + " (" + String.format(java.util.Locale.ROOT, "%.1f", lastInventory.quota.usagePercent) + "%)";
            context.text(this.font, inventorySummary, Math.max(10, this.width - this.font.width(inventorySummary) - 10), 10, 0x9AA5B1, true);
        }

        int tabColumns = Math.max(1, (this.width - 20) / TAB_STEP);
        int tabRows = Math.max(1, (int) Math.ceil((double) Math.max(1, categories.size()) / tabColumns));
        Component tooltip = null;
        for (int i = 0; i < categories.size(); i++) {
            int x = 10 + (i % tabColumns) * TAB_STEP;
            int y = tabY + (i / tabColumns) * TAB_STEP;
            boolean active = i == selectedCategory;
            boolean hovered = mouseX >= x && mouseX < x + TAB_SIZE && mouseY >= y && mouseY < y + TAB_SIZE;
            context.fill(x, y, x + TAB_SIZE, y + TAB_SIZE, active ? 0xFF356A52 : hovered ? 0xFF354252 : 0xFF242A33);
            ItemStack icon = categories.get(i).icon;
            if (icon != null && !icon.isEmpty()) context.item(icon, x + 4, y + 4);
            if (hovered) tooltip = categories.get(i).label;
        }

        gridY = tabY + tabRows * TAB_STEP + 15;
        gridBottom = this.height - (this.width < 390 ? 77 : 51);
        List<CloudStoreModels.Item> visible = visibleItems();
        int columns = Math.max(1, (this.width - 20) / TILE_STEP);
        int rows = Math.max(1, (gridBottom - gridY) / TILE_STEP);
        int maxScroll = Math.max(0, (int) Math.ceil((double) visible.size() / columns) - rows);
        scroll = Math.min(scroll, maxScroll);
        int start = scroll * columns;
        int x0 = Math.max(10, (this.width - columns * TILE_STEP) / 2);
        for (int i = 0; i < rows * columns && start + i < visible.size(); i++) {
            CloudStoreModels.Item item = visible.get(start + i);
            int x = x0 + (i % columns) * TILE_STEP;
            int y = gridY + (i / columns) * TILE_STEP;
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
                var matrices = context.pose();
                matrices.pushMatrix();
                matrices.translate(x + TILE_SIZE / 2.0f, y + TILE_SIZE / 2.0f);
                matrices.scale(ITEM_SCALE, ITEM_SCALE);
                matrices.translate(-8.0f, -8.0f);
                context.item(stack, 0, 0);
                matrices.popMatrix();
            }
            String amount = compact(item.amount);
            if (!stack.isEmpty()) context.itemDecorations(this.font, stack, x + 17, y + 17, amount);
            if (selectedAmount > 0) {
                String selectedText = compact(selectedAmount);
                context.text(this.font, selectedText, x + 1, y, 0x9FFFC5, true);
            }
            if (hovered) {
                tooltip = Component.literal(item.displayName + "  可取 " + item.amount + availabilitySuffix(item));
            }
        }

        if (visible.isEmpty() && status.isEmpty()) {
            context.centeredText(this.font, Component.literal("此分类没有匹配物品"), this.width / 2, (gridY + gridBottom) / 2, 0xAAB4C0);
        }

        int bottomY = this.height - 27;
        boolean twoRowBottom = this.width < 390;
        context.text(this.font, Component.literal("领取玩家"), 10, targetField.getY() - 11, 0xC5CED8, true);
        long totalSelected = selected.values().stream().mapToLong(Long::longValue).sum();
        String summary = "已选 " + selected.size() + " 种 / " + totalSelected + " 件";
        int summaryX = targetField.getX() + targetField.getWidth() + 8;
        int maxSummaryWidth = Math.max(0, clearButton.getX() - summaryX - 8);
        if (!twoRowBottom && maxSummaryWidth > 40) {
            context.text(this.font, this.font.plainSubstrByWidth(summary, maxSummaryWidth), summaryX, bottomY + 6, 0xD4D9E0, true);
        }
        String displayStatus = !status.isEmpty() ? status : activeStatus();
        if (!displayStatus.isEmpty()) {
            displayStatus = this.font.plainSubstrByWidth(displayStatus, Math.max(80, this.width - 20));
            context.centeredText(this.font, Component.literal(displayStatus), this.width / 2, gridY - 11, statusColor);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
        renderTargetSuggestions(context, mouseX, mouseY);
        if (tooltip != null) context.setTooltipForNextFrame(this.font, tooltip, mouseX, mouseY);
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

    record BoxGroup(String label, String itemId, int boxCount, long perBoxAmount, List<CloudStoreModels.Item> contents) {
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
            BoxGroup existing = groups.get(key);
            String label = safe(row.displayName).isBlank() ? "潜影盒" : row.displayName;
            groups.put(key, existing == null
                ? new BoxGroup(label, row.itemId, add, perBox, row.shulkerContents)
                : new BoxGroup(existing.label, existing.itemId, existing.boxCount + add, perBox, existing.contents));
        }
        return List.copyOf(groups.values());
    }

    private boolean isShiftDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (click.button() == 0 && clickTargetSuggestion(mouseX, mouseY)) return true;
        if (click.button() == 0 && mouseX >= targetField.getX() && mouseX < targetField.getX() + targetField.getWidth()
            && mouseY >= targetField.getY() && mouseY < targetField.getY() + targetField.getHeight()) {
            targetField.setFocused(true);
            setFocused((GuiEventListener) targetField);
            targetField.mouseClicked(click, doubled);
            refreshTargetSuggestions();
            targetSuggestionsOpen = !targetSuggestions.isEmpty();
            return true;
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
                    int localY = (int) (mouseY - gridY);
                    int column = localX / TILE_STEP;
                    int row = localY / TILE_STEP;
                    int index = scroll * columns + row * columns + column;
                    if (localX % TILE_STEP < TILE_SIZE && localY % TILE_STEP < TILE_SIZE && index >= 0 && index < visible.size()) {
                        CloudStoreModels.Item item = visible.get(index);
                        CloudStoreClient.setScreen(this.minecraft, new WithdrawScreen(this, item, selected.getOrDefault(item.itemKey, 0L)));
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
    public void onClose() {
        loadInventoryOnly(false, true);
        if (this.minecraft != null) CloudStoreClient.setScreen(this.minecraft, parent);
    }

    private ItemStack itemStack(String id) {
        try {
            Identifier identifier = Identifier.tryParse(id);
            return identifier == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.getValue(identifier));
        } catch (RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }

    private ItemStack itemStack(CloudStoreModels.Item item) {
        ItemStack stack = itemStack(item.itemId);
        if (stack.isEmpty() || !"minecraft:firework_rocket".equals(item.itemId)) return stack;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"flightDuration\\\"\\s*:\\s*(\\d+)").matcher(safe(item.metaJson));
        if (!matcher.find()) return stack;
        try {
            Class<?> typeClass = Class.forName("net.minecraft.core.component.DataComponentType");
            Object componentType = Class.forName("net.minecraft.core.component.DataComponents").getField("FIREWORKS").get(null);
            Object fireworks = Class.forName("net.minecraft.world.item.component.Fireworks")
                .getConstructor(int.class, List.class).newInstance(Integer.parseInt(matcher.group(1)), List.of());
            stack.getClass().getMethod("set", typeClass, Object.class).invoke(stack, componentType, fireworks);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return stack;
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

    private record Category(Component label, ItemStack icon, Set<Item> members, boolean all) {
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

    private record LoadResult(CloudStoreModels.MeResponse me, String ownerUuid, CloudStoreModels.InventoryResponse inventory) {
    }
}
