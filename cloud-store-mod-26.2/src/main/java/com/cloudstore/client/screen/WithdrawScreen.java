package com.cloudstore.client.screen;

import com.cloudstore.client.api.CloudStoreModels;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
    private static final int TILE_SIZE = 34;
    private static final int TILE_STEP = 34;
    private static final float ITEM_SCALE = 1.25f;

    private final CloudStoreScreen parent;
    private final CloudStoreModels.Item item;
    private List<CloudStoreScreen.BoxGroup> groups;
    private long[] groupSelected;
    private long looseSelected;
    private EditBox amountField;
    private String status = "";
    private int titleY;

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
        int fieldY = titleY + 70;
        amountField = new EditBox(this.font, center - 100, fieldY, 146, 20, Component.literal("取货总数"));
        amountField.setHint(Component.literal("由下方明细选择自动汇总"));
        amountField.setMaxLength(12);
        amountField.setEditable(false);
        this.addRenderableWidget(amountField);
        this.addRenderableWidget(Button.builder(Component.literal("全部"), button -> selectAll())
            .bounds(center + 52, fieldY, 48, 20).build());

        int stepY = fieldY + 28;
        int stepX = center - 108;
        this.addRenderableWidget(Button.builder(Component.literal("+64"), button -> addLoose(64))
            .bounds(stepX, stepY, 48, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("-64"), button -> addLoose(-64))
            .bounds(stepX + 52, stepY, 48, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+1728"), button -> addBoxed(1728))
            .bounds(stepX + 104, stepY, 56, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("-1728"), button -> addBoxed(-1728))
            .bounds(stepX + 164, stepY, 56, 20).build());

        int actionY = stepY + 28;
        this.addRenderableWidget(Button.builder(Component.literal("加入取货清单"), button -> addToCart())
            .bounds(center - 100, actionY, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("移除"), button -> removeFromCart())
            .bounds(center + 2, actionY, 46, 20).build()).active = totalSelected() > 0;
        this.addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
            .bounds(center + 54, actionY, 46, 20).build());
        this.setInitialFocus(amountField);
        refreshAmountField();
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
                    if (newGroups.get(i).itemId().equals(source.sourceKey())) {
                        newSelected[i] = clampGroup(newGroups.get(i), source.amount());
                        break;
                    }
                }
            }
        } else {
            looseSelected = clampLoose(looseSelected);
            for (int i = 0; i < newGroups.size(); i++) {
                for (int j = 0; j < oldGroups.size(); j++) {
                    if (oldGroups.get(j).itemId().equals(newGroups.get(i).itemId())) {
                        newSelected[i] = clampGroup(newGroups.get(i), oldSelected[j]);
                        break;
                    }
                }
            }
        }
        groups = newGroups;
        groupSelected = newSelected;
    }

    private long looseCapacity() {
        return Math.max(0, item.looseAmount);
    }

    private long groupCapacity(CloudStoreScreen.BoxGroup group) {
        return Math.max(0, group.perBoxAmount() * group.boxCount());
    }

    private long clampLoose(long value) {
        return Math.max(0, Math.min(looseCapacity(), value));
    }

    private long clampGroup(CloudStoreScreen.BoxGroup group, long value) {
        return Math.max(0, Math.min(groupCapacity(group), value));
    }

    private long totalSelected() {
        long total = looseSelected;
        for (long value : groupSelected) total += value;
        return total;
    }

    private void refreshAmountField() {
        if (amountField != null) amountField.setValue(String.valueOf(totalSelected()));
        status = "";
    }

    private void addLoose(long delta) {
        looseSelected = clampLoose(looseSelected + delta);
        refreshAmountField();
    }

    private void addBoxed(long delta) {
        if (groups != null) {
            if (delta > 0) {
                for (int i = 0; i < groups.size(); i++) {
                    if (groupSelected[i] + delta <= groupCapacity(groups.get(i))) {
                        groupSelected[i] += delta;
                        refreshAmountField();
                        return;
                    }
                }
            } else {
                for (int i = 0; i < groups.size(); i++) {
                    if (groupSelected[i] > 0) {
                        groupSelected[i] = Math.max(0, groupSelected[i] + delta);
                        refreshAmountField();
                        return;
                    }
                }
            }
        }
        looseSelected = clampLoose(looseSelected + delta);
        refreshAmountField();
    }

    private void selectAll() {
        looseSelected = looseCapacity();
        for (int i = 0; i < groups.size(); i++) groupSelected[i] = groupCapacity(groups.get(i));
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
                sources.add(new CloudStoreScreen.SourceSelection(group.itemId(), group.label(), true,
                    group.perBoxAmount(), groupSelected[i]));
            }
        }
        parent.setSelectedSources(item, sources);
        onClose();
    }

    private void removeFromCart() {
        parent.setSelectedSources(item, List.of());
        onClose();
    }

    private void adjustAmount(long delta) {
        long current;
        try {
            current = Long.parseLong(amountField.getValue().trim());
        } catch (NumberFormatException error) {
            current = 0;
        }
        long step = Math.abs(delta);
        long adjusted;
        if (delta > 0) {
            long groupsCount = current / step;
            adjusted = groupsCount >= Long.MAX_VALUE / step ? Long.MAX_VALUE : (groupsCount + 1) * step;
        } else {
            adjusted = current <= 0 ? 0 : (current - 1) / step * step;
        }
        adjusted = Math.max(0, Math.min(item.amount, adjusted));
        amountField.setValue(String.valueOf(adjusted));
        status = "";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Integer tile = tileIndexAt(mouseX, mouseY);
        if (tile != null && verticalAmount != 0) {
            adjustTile(tile, verticalAmount > 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        Integer tile = tileIndexAt(click.x(), click.y());
        if (tile != null && (click.button() == 0 || click.button() == 1)) {
            adjustTile(tile, click.button() == 0 ? 1 : -1);
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private Integer tileIndexAt(double mouseX, double mouseY) {
        int listX = this.width / 2 - 100;
        int tileY = detailTileY();
        if (mouseY < tileY || mouseY >= tileY + TILE_SIZE) return null;
        int localX = (int) (mouseX - listX);
        if (localX < 0 || localX % TILE_STEP >= TILE_SIZE) return null;
        int index = localX / TILE_STEP;
        boolean hasLoose = item.looseAmount > 0;
        if (hasLoose) {
            if (index == 0) return -1;
            index -= 1;
        }
        int maxTiles = Math.max(1, (this.width - 10 - listX) / TILE_STEP);
        int capacity = maxTiles - (hasLoose ? 1 : 0);
        if (index < 0 || index >= capacity || groups == null || index >= groups.size()) return null;
        return index;
    }

    private void adjustTile(int tile, int delta) {
        if (tile == -1) {
            looseSelected = clampLoose(looseSelected + delta);
        } else {
            CloudStoreScreen.BoxGroup group = groups.get(tile);
            groupSelected[tile] = clampGroup(group, groupSelected[tile] + delta);
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
        ItemStack stack = itemStack(item.itemId);
        if (!stack.isEmpty()) context.item(stack, this.width / 2 - 8, titleY + 18);
        context.centeredText(this.font, Component.literal(item.displayName), this.width / 2, titleY + 40, 0xE5E7EB);
        context.centeredText(this.font,
            Component.literal("可取 " + item.amount + availabilitySuffix()), this.width / 2, titleY + 51, 0xAAB4C0);
        context.text(this.font, Component.literal("取货总数（点击下方明细：左键+1 右键-1）"), this.width / 2 - 100, titleY + 59, 0xC5CED8, true);
        Component detailTooltip = renderStorageDetails(context, mouseX, mouseY);
        if (!status.isEmpty()) {
            context.centeredText(this.font, Component.literal(status), this.width / 2, this.height - 12, 0xF1B36B);
        }
        super.extractRenderState(context, mouseX, mouseY, delta);
        boolean previewShown = renderShulkerPreviewIfNeeded(context, mouseX, mouseY);
        if (detailTooltip != null && !previewShown) {
            context.setTooltipForNextFrame(this.font, detailTooltip, mouseX, mouseY);
        }
    }

    private Component renderStorageDetails(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int listX = this.width / 2 - 100;
        int listY = titleY + 152;
        int available = this.height - 24 - listY;
        if (available < TILE_SIZE) {
            if (available >= 10) {
                context.text(this.font, Component.literal("散装 " + item.looseAmount + "，盒内 " + item.boxedAmount), listX, listY, 0xAAB4C0, true);
            }
            return null;
        }
        context.text(this.font, Component.literal("存放明细（+1728 优先盒子，无盒子则散装）"), listX, listY, 0xC5CED8, true);
        int tileY = detailTileY();
        int x = listX;
        int maxTiles = Math.max(1, (this.width - 10 - x) / TILE_STEP);
        int used = 0;
        Component tooltip = null;
        if (item.looseAmount > 0) {
            tooltip = drawDetailTile(context, itemStack(item.itemId), compact(item.looseAmount),
                looseSelected > 0 ? compact(looseSelected) : null, looseSelected > 0,
                "散装 " + item.looseAmount + " · 已选 " + looseSelected, x, tileY, mouseX, mouseY);
            x += TILE_STEP;
            used++;
        }
        int overflow = Math.max(0, used + groups.size() - maxTiles);
        int shown = groups.size() - overflow;
        for (int i = 0; i < shown; i++) {
            CloudStoreScreen.BoxGroup group = groups.get(i);
            String tooltipText = group.label() + " ×" + group.boxCount() + "盒 · 每盒 " + group.perBoxAmount()
                + " 件 · 已选 " + groupSelected[i] + " 件";
            Component groupTooltip = drawDetailTile(context, itemStack(group.itemId()), "×" + group.boxCount(),
                groupSelected[i] > 0 ? compact(groupSelected[i]) : null, groupSelected[i] > 0,
                tooltipText, x, tileY, mouseX, mouseY);
            if (groupTooltip != null) tooltip = groupTooltip;
            x += TILE_STEP;
        }
        if (overflow > 0) {
            context.text(this.font, Component.literal("+" + overflow + " 种"), x + 2, tileY + TILE_SIZE / 2 - 4, 0x8A94A0, true);
        } else if (groups.isEmpty() && item.boxedAmount > 0) {
            context.text(this.font, Component.literal("盒内 " + item.boxedAmount), x + 8, tileY + TILE_SIZE / 2 - 4, 0xAAB4C0, true);
        }
        return tooltip;
    }

    private Component drawDetailTile(GuiGraphicsExtractor context, ItemStack stack, String countText, String selectedText,
                                     boolean selected, String tooltip, int x, int y, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + TILE_SIZE && mouseY >= y && mouseY < y + TILE_SIZE;
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
            context.text(this.font, Component.literal(selectedText), x + 3, y + 1, 0x9FFFC5, true);
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

    private int detailTileY() {
        int listY = titleY + 152;
        int tileY = listY + 12;
        if (tileY + TILE_SIZE > this.height - 10) tileY = listY;
        return tileY;
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
