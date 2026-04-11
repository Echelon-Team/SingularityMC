package com.singularity.launcher.ui.screens.instances

/**
 * 9 zakładek drill-down InstancePanel.
 *
 * Sub 4 scope: MODS full scanner, PRE_GEN GUI real (backend Sub 5), reszta = real file-list tabs.
 */
enum class InstanceTab(val i18nKey: String) {
    MODS("instance_panel.tab.mods"),
    RESOURCE_PACKS("instance_panel.tab.resource_packs"),
    SHADERS("instance_panel.tab.shaders"),
    DATAPACKS("instance_panel.tab.datapacks"),
    SERVERS("instance_panel.tab.servers"),
    WORLDS("instance_panel.tab.worlds"),
    BACKUPS("instance_panel.tab.backups"),
    SCREENSHOTS("instance_panel.tab.screenshots"),
    PRE_GEN("instance_panel.tab.pre_gen")
}
