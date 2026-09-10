package com.cloudstore.client.screen;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;

import java.nio.file.Files;
import java.nio.file.Path;

final class SchematicImportBrowser extends GuiSchematicBrowserBase implements ISelectionListener<DirectoryEntry> {
    private final CloudStoreScreen cloudStore;

    SchematicImportBrowser(CloudStoreScreen cloudStore) {
        super(12, 24);
        this.cloudStore = cloudStore;
        this.title = "导入原理图";
    }

    void open() {
        setParent(cloudStore);
        GuiBase.openGui(this);
    }

    @Override
    public String getBrowserContext() {
        return "cloud_store_import";
    }

    @Override
    public Path getDefaultDirectory() {
        return DataManager.getSchematicsBaseDirectory();
    }

    @Override
    public void initGui() {
        super.initGui();
        int y = getScreenHeight() - 26;
        addButton(new ButtonGeneric(12, y, 62, 20, "导入"), new ButtonListener(this, true));
        addButton(new ButtonGeneric(getScreenWidth() - 62, y, 52, 20, "取消"), new ButtonListener(this, false));
    }

    @Override
    public void onSelectionChange(DirectoryEntry entry) {
    }

    @Override
    protected ISelectionListener<DirectoryEntry> getSelectionListener() {
        return this;
    }

    static LitematicaSchematic load(Path file) {
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) return null;
        FileType type = FileType.fromFile(file);
        if (type == FileType.LITEMATICA_SCHEMATIC) return LitematicaSchematic.createFromFile(file.getParent(), file.getFileName().toString(), type);
        if (type == FileType.SCHEMATICA_SCHEMATIC) return WorldUtils.convertSchematicaSchematicToLitematicaSchematic(file.getParent(), file.getFileName().toString(), false, null);
        if (type == FileType.SPONGE_SCHEMATIC) return WorldUtils.convertSpongeSchematicToLitematicaSchematic(file.getParent(), file.getFileName().toString());
        if (type == FileType.VANILLA_STRUCTURE) return WorldUtils.convertStructureToLitematicaSchematic(file.getParent(), file.getFileName().toString());
        return null;
    }

    private void importSelected() {
        DirectoryEntry entry = getListWidget() == null ? null : getListWidget().getLastSelectedEntry();
        if (entry == null) {
            addMessage(Message.MessageType.ERROR, "请先选择一个原理图文件");
            return;
        }
        cloudStore.importSchematic(entry.getFullPath());
        GuiBase.openGui(cloudStore);
    }

    private void cancel() {
        GuiBase.openGui(cloudStore);
    }

    private record ButtonListener(SchematicImportBrowser browser, boolean importFile) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            if (importFile) browser.importSelected();
            else browser.cancel();
        }
    }
}
