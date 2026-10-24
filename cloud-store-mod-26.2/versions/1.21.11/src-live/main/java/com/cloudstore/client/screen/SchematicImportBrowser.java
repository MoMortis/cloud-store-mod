package com.cloudstore.client.screen;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import java.nio.file.Files;
import java.nio.file.Path;

final class SchematicImportBrowser extends GuiSchematicBrowserBase implements ISelectionListener<DirectoryEntry> {
    private final CloudStoreScreen cloudStore;
    SchematicImportBrowser(CloudStoreScreen cloudStore) { super(12, 24); this.cloudStore = cloudStore; this.title = "导入原理图"; }
    void open() { setParent(cloudStore); GuiBase.openGui(this); }
    @Override public String getBrowserContext() { return "cloud_store_import"; }
    @Override public Path getDefaultDirectory() { return DataManager.getSchematicsBaseDirectory(); }
    @Override public void initGui() { super.initGui(); int y = getScreenHeight() - 26; int x = 12; ButtonGeneric b1 = new ButtonGeneric(x, y, -1, 20, "导入"); addButton(b1, new Buttons(this, 1)); x = b1.getX() + b1.getWidth() + 4; ButtonGeneric b2 = new ButtonGeneric(x, y, -1, 20, "仅导入缺失材料"); addButton(b2, new Buttons(this, 2)); x = b2.getX() + b2.getWidth() + 4; ButtonGeneric b3 = new ButtonGeneric(x, y, -1, 20, "仅导入容器中物品"); addButton(b3, new Buttons(this, 3)); x = b3.getX() + b3.getWidth() + 4; addButton(new ButtonGeneric(x, y, -1, 20, "仅导入容器中且背包缺失物品"), new Buttons(this, 4)); addButton(new ButtonGeneric(getScreenWidth() - 12, y, -1, true, "取消"), new Buttons(this, 0)); }
    @Override public void onSelectionChange(DirectoryEntry entry) { }
    @Override protected ISelectionListener<DirectoryEntry> getSelectionListener() { return this; }
    static LitematicaSchematic load(Path file) {
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) return null;
        FileType type = FileType.fromFile(file);
        if (type == FileType.LITEMATICA_SCHEMATIC) return LitematicaSchematic.createFromFile(file.getParent(), file.getFileName().toString(), type);
        if (type == FileType.SCHEMATICA_SCHEMATIC) return WorldUtils.convertSchematicaSchematicToLitematicaSchematic(file.getParent(), file.getFileName().toString(), false, null);
        if (type == FileType.SPONGE_SCHEMATIC) return WorldUtils.convertSpongeSchematicToLitematicaSchematic(file.getParent(), file.getFileName().toString());
        if (type == FileType.VANILLA_STRUCTURE) return WorldUtils.convertStructureToLitematicaSchematic(file.getParent(), file.getFileName().toString());
        return null;
    }
    private void select(boolean missingOnly) { DirectoryEntry entry = getListWidget() == null ? null : getListWidget().getLastSelectedEntry(); if (entry != null) cloudStore.importSchematic(entry.getFullPath(), missingOnly); GuiBase.openGui(cloudStore); }
    private void selectContainers(boolean missingOnly) { DirectoryEntry entry = getListWidget() == null ? null : getListWidget().getLastSelectedEntry(); if (entry != null) cloudStore.importSchematicContainers(entry.getFullPath(), missingOnly); GuiBase.openGui(cloudStore); }
    // mode: 0=取消，1=全部导入，2=仅导入缺失材料，3=仅导入容器中物品，4=仅导入容器中且背包缺失物品
    private record Buttons(SchematicImportBrowser browser, int mode) implements IButtonActionListener { @Override public void actionPerformedWithButton(ButtonBase button, int mouseButton) { if (mode == 1) browser.select(false); else if (mode == 2) browser.select(true); else if (mode == 3) browser.selectContainers(false); else if (mode == 4) browser.selectContainers(true); else GuiBase.openGui(browser.cloudStore); } }
}
