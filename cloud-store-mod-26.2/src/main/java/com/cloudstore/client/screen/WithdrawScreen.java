package com.cloudstore.client.screen;

import com.cloudstore.client.CloudStoreClient;
import com.cloudstore.client.api.CloudStoreModels;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class WithdrawScreen extends Screen {
    private static final int TILE_SIZE = 68;
    private static final int TILE_STEP = 68;
    private static final float ITEM_SCALE = 2.0f; // 格子边长为原来的两倍，图标等比放大

    private final CloudStoreScreen parent;
    private final CloudStoreModels.Item item;
    private List<CloudStoreScreen.BoxGroup> groups;
    private long[] groupSelected;
    private long looseSelected;
    private Button favoriteButton;
    private Button allButton;
    private Button removeButton;
    private boolean favorite;
    private String status = "";
    private int titleY;
    // 明细区纵向滚动（滑块拖动），单位：行
    private int detailScroll;
    private float detailScrollAnim;
    private boolean detailDrag;

    public WithdrawScreen(CloudStoreScreen parent, CloudStoreModels.Item item, long selectedAmount) {
        super(Component.literal("设置取货数量"));
        this.parent = parent;
        this.item = item;
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        titleY = Math.max(12, Math.min(38, (this.height - 180) / 2));
        reloadGroups(false);

        int actionY = titleY + 70;
        this.addRenderableWidget(Button.builder(Component.literal("加入取货清单"), button -> addToCart())
            .bounds(center - 152, actionY, 96, 20).build());
        removeButton = this.addRenderableWidget(Button.builder(Component.literal("移除"), button -> removeFromCart())
            .bounds(center - 51, actionY, 46, 20).build());
        removeButton.active = totalSelected() > 0;
        allButton = this.addRenderableWidget(Button.builder(Component.literal("全部"), button -> selectAll())
            .bounds(center, actionY, 44, 20).build());
        favoriteButton = Button.builder(Component.literal(""), button -> toggleFavorite())
            .bounds(center + 49, actionY, 52, 20).build();
        this.addRenderableWidget(favoriteButton);
        this.addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
            .bounds(center + 106, actionY, 46, 20).build());
        refreshFavoriteButton();
    }

    private void refreshFavoriteButton() {
        favorite = CloudStoreClient.config.favorites.contains(item.itemKey);
        if (favoriteButton != null) favoriteButton.setMessage(Component.literal(favorite ? "取消收藏" : "收藏"));
    }

    private void toggleFavorite() {
        String key = item.itemKey;
        boolean removed = CloudStoreClient.config.favorites.remove(key);
        if (!removed) CloudStoreClient.config.favorites.add(key);
        try {
            CloudStoreClient.config.save();
        } catch (RuntimeException error) {
            status = "收藏保存失败：" + error.getMessage();
        }
        favorite = !removed;
        favoriteButton.setMessage(Component.literal(favorite ? "取消收藏" : "收藏"));
    }

    private void reloadGroups(boolean keepEdits) {
        List<CloudStoreScreen.BoxGroup> newGroups = parent.boxGroupsFor(item);
        long[] newSelected = new long[newGroups.size()];
        List<CloudStoreScreen.BoxGroup> oldGroups = groups;
        long[] oldSelected = groupSelected;
        if (oldGroups == null || oldSelected == null || !keepEdits) {
            for (CloudStoreScreen.SourceSelection source : parent.getSelectedSources(item)) {
                if (!source.box()) {
                    looseSelected = clampLoose(source.amount());
                    continue;
                }
                for (int i = 0; i < newGroups.size(); i++) {
                    // 盒子选中项的 sourceKey 存的是盒子行的 itemKey（variantKey），必须按 sourceKey 匹配
                    if (newGroups.get(i).sourceKey().equals(source.sourceKey())) {
                        long perBox = Math.max(1, newGroups.get(i).perBoxAmount());
                        newSelected[i] = clampGroup(newGroups.get(i), source.amount() / perBox); // 物品数 → 盒数
                        break;
                    }
                }
            }
        } else {
            looseSelected = clampLoose(looseSelected);
            for (int i = 0; i < newGroups.size(); i++) {
                for (int j = 0; j < oldGroups.size(); j++) {
                    if (oldGroups.get(j).sourceKey().equals(newGroups.get(i).sourceKey())) {
                        newSelected[i] = clampGroup(newGroups.get(i), oldSelected[j]);
                        break;
                    }
                }
            }
        }
        groups = newGroups;
        groupSelected = newSelected;
        // 盒子优先保留，超出总可取量的部分从散货里裁掉
        long overflow = totalSelected() - totalCap();
        if (overflow > 0) looseSelected = Math.max(0, looseSelected - overflow);
    }

    private long totalCap() {
        // 上限 = 总可取量（散装 + 盒内；服务端有拆盒机制）
        return Math.max(0, item.amount);
    }

    // 盒子按“盒”计数，这里把已选盒数换算成盒内物品数
    private long groupTotal() {
        if (groups == null || groupSelected == null) return 0; // 初始化阶段可能尚未赋值
        long total = 0;
        int size = Math.min(groups.size(), groupSelected.length);
        for (int i = 0; i < size; i++) total += groupSelected[i] * groups.get(i).perBoxAmount();
        return total;
    }

    private long groupCapacity(CloudStoreScreen.BoxGroup group) {
        return Math.max(0, group.boxCount()); // 单位：盒
    }

    private long clampLoose(long value) {
        // 上限 = 散货 + 盒内 - 已选中盒内物品数
        return Math.max(0, Math.min(totalCap() - groupTotal(), value));
    }

    private long clampGroup(CloudStoreScreen.BoxGroup group, long value) {
        return Math.max(0, Math.min(groupCapacity(group), value));
    }

    private long totalSelected() {
        // 盒子按盒数存储，这里换算成物品数后与散货相加
        return looseSelected + groupTotal();
    }

    private void refreshAmountField() {
        status = "";
        if (removeButton != null) removeButton.active = totalSelected() > 0;
    }

    private void selectAll() {
        for (int i = 0; i < groups.size(); i++) groupSelected[i] = groupCapacity(groups.get(i));
        looseSelected = Math.max(0, totalCap() - groupTotal());
        refreshAmountField();
    }

    private void addToCart() {
        if (totalSelected() <= 0) {
            status = "请先选择数量";
            return;
        }
        List<CloudStoreScreen.SourceSelection> sources = new ArrayList<>();
        if (looseSelected > 0) {
            sources.add(new CloudStoreScreen.SourceSelection(item.itemKey, item.displayName, false, 0, looseSelected));
        }
        for (int i = 0; i < groups.size(); i++) {
            if (groupSelected[i] > 0) {
                CloudStoreScreen.BoxGroup group = groups.get(i);
                sources.add(new CloudStoreScreen.SourceSelection(group.sourceKey(), group.label(), true,
                    group.perBoxAmount(), groupSelected[i] * group.perBoxAmount()));
            }
        }
        parent.setSelectedSources(item, sources);
        onClose();
    }

    private void removeFromCart() {
        parent.setSelectedSources(item, List.of());
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Integer tile = tileIndexAt(mouseX, mouseY);
        if (tile != null && verticalAmount != 0) {
            adjustTileWithModifiers(tile, verticalAmount > 0);
            return true;
        }
        // 悬停在明细区但不在格子上时，滚轮滚动明细区
        if (verticalAmount != 0 && detailMaxScroll() > 0
            && mouseY >= detailTileTop() && mouseY < detailTileTop() + maxDetailRows() * (long) TILE_STEP) {
            detailScroll = Math.max(0, Math.min(detailMaxScroll(), detailScroll + (verticalAmount > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        // 右键“全部”按钮：清空全部选中
        if (allButton != null && click.button() == 1 && inButtonBounds(allButton, click.x(), click.y())) {
            clearAll();
            return true;
        }
        // 左键按住明细区滑块：开始拖动
        if (detailMaxScroll() > 0 && click.button() == 0
            && click.x() >= this.width - 9 && click.x() < this.width - 3
            && click.y() >= detailTileTop() && click.y() < detailTileTop() + maxDetailRows() * (long) TILE_STEP) {
            detailDrag = true;
            updateDetailScrollFromDrag(click.y());
            return true;
        }
        Integer tile = tileIndexAt(click.x(), click.y());
        if (tile != null && (click.button() == 0 || click.button() == 1)) {
            adjustTileWithModifiers(tile, click.button() == 0);
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dragX, double dragY) {
        if (detailDrag) {
            updateDetailScrollFromDrag(click.y());
            return true;
        }
        return super.mouseDragged(click, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        detailDrag = false;
        return super.mouseReleased(click);
    }

    // 单击与滚轮共用：Alt=全部/清空，Shift=±64（散货为个，盒子为盒）
    private void adjustTileWithModifiers(int tile, boolean increase) {
        if (isAltDown()) {
            if (tile == -1) {
                looseSelected = increase ? clampLoose(Long.MAX_VALUE) : 0;
            } else {
                CloudStoreScreen.BoxGroup group = groups.get(tile);
                groupSelected[tile] = increase ? groupCapacity(group) : 0;
                long overflow = totalSelected() - totalCap();
                if (overflow > 0) looseSelected = Math.max(0, looseSelected - overflow);
            }
        } else {
            int delta = increase ? 1 : -1;
            if (isShiftDown()) delta *= 64;
            adjustTile(tile, delta);
        }
        refreshAmountField();
    }

    private void clearAll() {
        looseSelected = 0;
        if (groupSelected != null) java.util.Arrays.fill(groupSelected, 0);
        refreshAmountField();
    }

    private boolean inButtonBounds(Button button, double mouseX, double mouseY) {
        return mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
            && mouseY >= button.getY() && mouseY < button.getY() + button.getHeight();
    }

    private Integer tileIndexAt(double mouseX, double mouseY) {
        int tileY = detailTileTop();
        // 屏幕过矮放不下一行格子时明细区不绘制（renderStorageDetails 提前返回），命中判定同步拒绝
        if (this.height - 24 - tileY < TILE_SIZE) return null;
        if (mouseY < tileY) return null;
        int perRow = tilesPerRow();
        int visibleRows = maxDetailRows();
        if (mouseY >= tileY + visibleRows * (long) TILE_STEP) return null;
        int row = (int) ((mouseY - tileY) / TILE_STEP) + detailScroll;
        int prev = row * perRow;
        int tileCount = groups.size() + 1;
        int totalRows = (tileCount + perRow - 1) / perRow;
        if (row >= totalRows) return null;
        int rowCount = Math.min(perRow, tileCount - prev);
        int localX = (int) (mouseX - rowStartX(rowCount));
        if (localX < 0 || localX % TILE_STEP >= TILE_SIZE) return null;
        int col = localX / TILE_STEP;
        if (col >= rowCount) return null;
        int index = prev + col;
        boolean hasLoose = true; // 散货格子恒显示
        if (hasLoose) {
            if (index == 0) return -1;
            index -= 1;
        }
        if (index < 0 || groups == null || index >= groups.size()) return null;
        return index;
    }

    private void adjustTile(int tile, int delta) {
        if (tile == -1) {
            looseSelected = clampLoose(looseSelected + delta);
        } else {
            CloudStoreScreen.BoxGroup group = groups.get(tile);
            groupSelected[tile] = clampGroup(group, groupSelected[tile] + delta);
            // 盒子优先，超出总可取量的部分从散货里裁掉
            long overflow = totalSelected() - totalCap();
            if (overflow > 0) looseSelected = Math.max(0, looseSelected - overflow);
        }
        refreshAmountField();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) com.cloudstore.client.CloudStoreClient.setScreen(this.minecraft, parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xED101116);
        context.centeredText(this.font, this.title, this.width / 2, titleY, 0xFFFFFF);
        ItemStack stack = parent != null ? parent.itemStack(item) : itemStack(item.itemId);
        if (!stack.isEmpty()) context.item(stack, this.width / 2 - 8, titleY + 18);
        context.centeredText(this.font, Component.literal(item.displayName), this.width / 2, titleY + 40, 0xE5E7EB);
        context.centeredText(this.font,
            Component.literal("可取 " + item.amount + availabilitySuffix()), this.width / 2, titleY + 51, 0xAAB4C0);
        Component detailTooltip = renderStorageDetails(context, mouseX, mouseY, delta);
        if (!status.isEmpty()) {
            context.centeredText(this.font, Component.literal(status), this.width / 2, this.height - 12, 0xF1B36B);
        }
        super.extractRenderState(context, mouseX, mouseY, delta);
        boolean previewShown = renderShulkerPreviewIfNeeded(context, mouseX, mouseY);
        if (detailTooltip != null && !previewShown) {
            context.setTooltipForNextFrame(this.font, detailTooltip, mouseX, mouseY);
        }
    }

    private Component renderStorageDetails(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        int listY = titleY + 152;
        int available = this.height - 24 - listY;
        if (available < TILE_SIZE) {
            if (available >= 10) {
                context.text(this.font, Component.literal("散装 " + item.looseAmount + "，盒内 " + item.boxedAmount), this.width / 2 - 60, listY, 0xAAB4C0, true);
            }
            return null;
        }
        int tileY = detailTileTop();
        int perRow = tilesPerRow();
        int visibleRows = maxDetailRows();
        int tileCount = groups.size() + 1; // 含恒显示的散货格子
        int totalRows = (tileCount + perRow - 1) / perRow;
        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (detailScroll > maxScroll) detailScroll = maxScroll;
        if (detailScroll < 0) detailScroll = 0;
        // 平滑滚动动画：动画值逐帧逼近目标行
        detailScrollAnim += (detailScroll - detailScrollAnim) * Math.min(1.0f, Math.max(0.0f, delta) * 0.45f);
        if (Math.abs(detailScroll - detailScrollAnim) < 0.002f) detailScrollAnim = detailScroll;
        if (detailScrollAnim < 0) detailScrollAnim = 0; // 与主界面一致：maxScroll 骤减时防止动画值越界导致整格空白
        if (detailScrollAnim > maxScroll) detailScrollAnim = maxScroll;
        int animBaseRow = (int) Math.floor(detailScrollAnim);
        float animFrac = detailScrollAnim - animBaseRow;
        int rowOffset = Math.round(-animFrac * TILE_STEP);
        Component tooltip = null;
        int gridLeft = rowStartX(perRow);
        int gridBottom = tileY + visibleRows * TILE_STEP;
        context.enableScissor(Math.max(0, gridLeft - 4), Math.max(0, tileY - 2),
            Math.min(this.width, gridLeft + perRow * TILE_STEP + 4), Math.min(this.height, gridBottom + 2));
        for (int t = 0; t < tileCount; t++) {
            int row = t / perRow;
            if (row < animBaseRow) continue; // 已滚出可视区上方
            int col = t % perRow;
            int rowCount = Math.min(perRow, tileCount - row * perRow);
            int x = rowStartX(rowCount) + col * TILE_STEP;
            // 行号要转成可视区行（减去已滚过的整行数），否则滚动结束后整个网格下移、底部行被整行跳过
            int y = tileY + (row - animBaseRow) * TILE_STEP + rowOffset;
            if (y + TILE_SIZE <= tileY || y >= gridBottom) continue;
            if (t == 0) {
                // 散货格子恒显示（没有散货时数量显示 0）；图标带变体数据（如烟花等级）
                tooltip = drawDetailTile(context, parent != null ? parent.itemStack(item) : itemStack(item.itemId), compact(item.looseAmount),
                    looseSelected > 0 ? compact(looseSelected) : null, looseSelected > 0,
                    "散装 " + item.looseAmount + " · 已选 " + looseSelected, x, y, mouseX, mouseY);
                continue;
            }
            CloudStoreScreen.BoxGroup group = groups.get(t - 1);
            String tooltipText = group.label() + " ×" + group.boxCount() + "盒 · 每盒 " + group.perBoxAmount()
                + " 件 · 已选 " + groupSelected[t - 1] + " 盒（共 " + (groupSelected[t - 1] * group.perBoxAmount()) + " 件）";
            Component groupTooltip = drawDetailTile(context, itemStack(group.itemId()), "×" + group.boxCount(),
                groupSelected[t - 1] > 0 ? groupSelected[t - 1] + "盒" : null, groupSelected[t - 1] > 0,
                tooltipText, x, y, mouseX, mouseY);
            if (groupTooltip != null) tooltip = groupTooltip;
        }
        context.disableScissor();
        if (groups.isEmpty() && item.boxedAmount > 0) {
            context.text(this.font, Component.literal("盒内 " + item.boxedAmount), rowStartX(1) + TILE_STEP + 8, tileY + TILE_SIZE / 2 - 4, 0xAAB4C0, true);
        }
        drawDetailScrollbar(context, tileY, visibleRows, totalRows, maxScroll, mouseX, mouseY);
        return tooltip;
    }

    // 明细区右侧的纵向滑块；返回滑块轨道区域是否被鼠标悬停
    private void drawDetailScrollbar(GuiGraphicsExtractor context, int tileY, int visibleRows, int totalRows, int maxScroll, int mouseX, int mouseY) {
        if (maxScroll <= 0) return;
        int trackX = this.width - 7;
        int trackH = visibleRows * TILE_STEP;
        int thumbH = Math.max(24, (int) ((long) trackH * visibleRows / totalRows));
        int thumbY = tileY + (trackH - thumbH) * detailScroll / maxScroll;
        boolean hot = detailDrag || (mouseX >= trackX && mouseX < trackX + 4 && mouseY >= tileY && mouseY < tileY + trackH);
        context.fill(trackX, tileY, trackX + 4, tileY + trackH, 0xFF1A212B);
        context.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, hot ? 0xFF5A6B80 : 0xFF3E4C5E);
    }

    // 拖动滑块时根据鼠标 y 更新滚动行数
    private void updateDetailScrollFromDrag(double mouseY) {
        int maxScroll = detailMaxScroll();
        if (maxScroll <= 0) {
            detailScroll = 0;
            return;
        }
        int trackH = maxDetailRows() * TILE_STEP;
        int visibleRows = maxDetailRows();
        int totalRows = visibleRows + maxScroll;
        int thumbH = Math.max(24, (int) ((long) trackH * visibleRows / totalRows));
        double usable = trackH - thumbH;
        double ratio = usable <= 0 ? 0 : (mouseY - detailTileTop() - thumbH / 2.0) / usable;
        detailScroll = (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * maxScroll);
    }

    private int detailMaxScroll() {
        if (groups == null) return 0;
        int perRow = tilesPerRow();
        int tileCount = groups.size() + 1;
        int totalRows = (tileCount + perRow - 1) / perRow;
        return Math.max(0, totalRows - maxDetailRows());
    }

    private Component drawDetailTile(GuiGraphicsExtractor context, ItemStack stack, String countText, String selectedText,
                                     boolean selected, String tooltip, int x, int y, int mouseX, int mouseY) {
        // 悬停判定夹到可视区内，被 scissor 裁掉的部分不响应（与点击判定范围一致）
        int visTop = detailTileTop();
        int visBottom = visTop + maxDetailRows() * TILE_STEP;
        boolean hovered = mouseX >= x && mouseX < x + TILE_SIZE
            && mouseY >= Math.max(y, visTop) && mouseY < Math.min(y + TILE_SIZE, visBottom);
        context.fill(x, y, x + TILE_SIZE, y + TILE_SIZE, hovered ? 0xFF354252 : 0xFF242A33);
        if (selected) {
            context.fill(x, y, x + TILE_SIZE, y + 2, 0xFF69D69A);
            context.fill(x, y + TILE_SIZE - 2, x + TILE_SIZE, y + TILE_SIZE, 0xFF69D69A);
            context.fill(x, y, x + 2, y + TILE_SIZE, 0xFF69D69A);
            context.fill(x + TILE_SIZE - 2, y, x + TILE_SIZE, y + TILE_SIZE, 0xFF69D69A);
        }
        if (!stack.isEmpty()) {
            var matrices = context.pose();
            matrices.pushMatrix();
            matrices.translate(x + TILE_SIZE / 2.0f, y + TILE_SIZE / 2.0f);
            matrices.scale(ITEM_SCALE, ITEM_SCALE);
            matrices.translate(-8.0f, -8.0f);
            context.item(stack, 0, 0);
            matrices.popMatrix();
        }
        if (selectedText != null) {
            context.text(this.font, Component.literal(selectedText), x + 2, y + 1, 0x9FFFC5, true);
        }
        if (countText != null) {
            int textWidth = this.font.width(countText);
            context.text(this.font, Component.literal(countText), x + TILE_SIZE - textWidth - 1, y + TILE_SIZE - 10, 0xFFFFFF, true);
        }
        return hovered ? Component.literal(tooltip) : null;
    }

    private boolean renderShulkerPreviewIfNeeded(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        CloudStoreScreen.BoxGroup group = hoveredBoxGroup(mouseX, mouseY);
        if (group == null || !isShiftDown()) return false;
        final int slot = 18;
        final int padding = 5;
        int width = padding * 2 + slot * 9;
        int height = padding * 2 + 14 + slot * 3;
        int x = mouseX + 12;
        int y = mouseY + 12;
        if (x + width > this.width - 4) x = mouseX - width - 12;
        if (y + height > this.height - 4) y = mouseY - height - 12;
        x = Math.max(4, x);
        y = Math.max(4, y);
        context.fill(x, y, x + width, y + height, 0xF0101014);
        context.fill(x, y, x + width, y + 1, 0xFF777777);
        context.fill(x, y + height - 1, x + width, y + height, 0xFF111111);
        context.text(this.font, Component.literal(group.label()), x + padding, y + 3, 0xFFFFFFFF, true);
        List<CloudStoreModels.Item> contents = group.contents() == null ? List.of() : group.contents();
        for (int index = 0; index < 27; index++) {
            int slotX = x + padding + (index % 9) * slot;
            int slotY = y + padding + 12 + (index / 9) * slot;
            context.fill(slotX, slotY, slotX + 17, slotY + 17, 0xFF8B8B8B);
            context.fill(slotX + 1, slotY + 1, slotX + 16, slotY + 16, 0xFF373737);
            if (index >= contents.size()) continue;
            CloudStoreModels.Item content = contents.get(index);
            ItemStack contentStack = itemStack(content.itemId);
            if (contentStack.isEmpty()) continue;
            contentStack.setCount((int) Math.min(Integer.MAX_VALUE, Math.max(1, content.amount)));
            context.item(contentStack, slotX + 1, slotY + 1);
            context.itemDecorations(this.font, contentStack, slotX + 1, slotY + 1, compact(content.amount));
        }
        return true;
    }

    private CloudStoreScreen.BoxGroup hoveredBoxGroup(double mouseX, double mouseY) {
        if (groups == null || groups.isEmpty()) return null;
        Integer tile = tileIndexAt(mouseX, mouseY);
        if (tile == null || tile < 0) return null;
        return groups.get(tile);
    }

    private boolean isShiftDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private boolean isAltDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    private int detailTileTop() {
        // “存放明细”标题移除后，格子直接从列表顶部开始
        return titleY + 152;
    }

    // 明细区最多能放几行（底部留 24px 给状态文字）
    private int maxDetailRows() {
        return Math.max(1, (this.height - 24 - detailTileTop()) / TILE_STEP);
    }

    // 每行最多放几个格子（左右各留 10px 边距）
    private int tilesPerRow() {
        return Math.max(1, (this.width - 20) / TILE_STEP);
    }

    // 某一行有 rowCount 个格子时，这一行的起始 x（整行居中）
    private int rowStartX(int rowCount) {
        return (this.width - rowCount * TILE_STEP) / 2;
    }

    private String availabilitySuffix() {
        if (item.boxedAmount <= 0) return "（散装）";
        if (item.looseAmount <= 0) return "（潜影盒内）";
        return "（散装 " + item.looseAmount + " + 盒内 " + item.boxedAmount + "）";
    }

    private ItemStack itemStack(String id) {
        try {
            Identifier identifier = Identifier.tryParse(id);
            return identifier == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.getValue(identifier));
        } catch (RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
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
}
