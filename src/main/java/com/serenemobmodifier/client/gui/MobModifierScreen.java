package com.serenemobmodifier.client.gui;

import com.serenemobmodifier.config.MobModifierConfig;
import com.serenemobmodifier.network.UpdateMobRulePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.Locale;

/**
 * Sodium/Embeddium-inspired configuration screen for Serene Mob Modifier.
 *
 * <h3>Layout</h3>
 * <ul>
 *   <li><b>Header</b>: Title + category tabs (All, Vanilla Aquatic, Hostile, Modded)</li>
 *   <li><b>Left panel</b>: Searchable, scrollable entity list with selection highlight</li>
 *   <li><b>Right panel</b>: Live config inspector with sliders and toggles for the selected entity</li>
 *   <li><b>Footer</b>: Apply + Done action buttons</li>
 * </ul>
 */
public class MobModifierScreen extends Screen {

    // ── Colour palette (ARGB) ───────────────────────────────────────────────
    private static final int COL_BG             = 0xD0101018;  // translucent near-black
    private static final int COL_HEADER         = 0xE8181824;
    private static final int COL_PANEL          = 0xC8141420;
    private static final int COL_PANEL_BORDER   = 0xFF2A2A3A;
    private static final int COL_ITEM_DEFAULT   = 0x60303048;
    private static final int COL_ITEM_HOVER     = 0x80404060;
    private static final int COL_ITEM_SELECTED  = 0xA0506090;
    private static final int COL_ACCENT         = 0xFF7C8FFF;  // cool periwinkle
    private static final int COL_ACCENT_DIM     = 0xFF4A5580;
    private static final int COL_TEXT           = 0xFFE0E0E8;
    private static final int COL_TEXT_DIM       = 0xFF909098;
    private static final int COL_SLIDER_BG      = 0xFF1E1E2C;
    private static final int COL_SLIDER_FILL    = 0xFF5C6FCC;
    private static final int COL_SLIDER_HANDLE  = 0xFFAABBFF;
    private static final int COL_TAB_ACTIVE     = 0xFF3A3A58;
    private static final int COL_TAB_INACTIVE   = 0xFF222234;
    private static final int COL_TOGGLE_ON      = 0xFF4CAF50;
    private static final int COL_TOGGLE_OFF     = 0xFF8B3A3A;
    private static final int COL_BTN_APPLY      = 0xFF3A6FBF;
    private static final int COL_BTN_DONE       = 0xFF2D4A2D;

    // ── Layout constants ────────────────────────────────────────────────────
    private static final int HEADER_H    = 52;
    private static final int FOOTER_H    = 40;
    private static final int TAB_H       = 22;
    private static final int LIST_ITEM_H = 20;
    private static final int PADDING     = 8;
    private static final int SEARCH_H          = 20;
    private static final int PANEL_BOTTOM_GAP  = 14;

    // ── Tab definitions ─────────────────────────────────────────────────────
    private enum Tab {
        ALL("All Registered Mobs"),
        AQUATIC("Vanilla Aquatic"),
        HOSTILE("Hostile Mobs"),
        MODDED("Modded / Third-Party");

        final String label;
        Tab(String label) { this.label = label; }
    }

    private static final Set<String> AQUATIC_IDS = Set.of(
            "minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish", "minecraft:pufferfish",
            "minecraft:squid", "minecraft:glow_squid", "minecraft:dolphin", "minecraft:turtle",
            "minecraft:axolotl", "minecraft:guardian", "minecraft:elder_guardian", "minecraft:drowned",
            "minecraft:tadpole", "minecraft:frog"
    );

    private static final Set<String> HOSTILE_IDS = Set.of(
            "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:spider",
            "minecraft:cave_spider", "minecraft:enderman", "minecraft:witch", "minecraft:slime",
            "minecraft:magma_cube", "minecraft:phantom", "minecraft:blaze", "minecraft:ghast",
            "minecraft:wither_skeleton", "minecraft:hoglin", "minecraft:zoglin", "minecraft:piglin",
            "minecraft:piglin_brute", "minecraft:pillager", "minecraft:vindicator", "minecraft:evoker",
            "minecraft:ravager", "minecraft:vex", "minecraft:guardian", "minecraft:elder_guardian",
            "minecraft:drowned", "minecraft:husk", "minecraft:stray", "minecraft:silverfish",
            "minecraft:endermite", "minecraft:warden", "minecraft:shulker", "minecraft:breeze",
            "minecraft:bogged"
    );

    // ── State ───────────────────────────────────────────────────────────────
    private Tab activeTab = Tab.ALL;
    private final List<String> allEntityIds = new ArrayList<>();
    private final List<String> filteredEntityIds = new ArrayList<>();
    private int selectedIndex = -1;
    private int listScrollOffset = 0;
    private int maxVisibleItems = 1;

    // Right inspector panel scroll state
    private int rightPanelScrollOffset = 0;
    private int rightContentHeight = 200;
    private boolean draggingRightScrollbar = false;

    // Pending edits keyed by entity ID
    private final Map<String, MobRule> pendingEdits = new LinkedHashMap<>();
    // Loaded rules from config keyed by entity ID
    private final Map<String, MobRule> loadedRules = new LinkedHashMap<>();
    // Track which entity IDs have been modified in this session
    private final Set<String> dirtyIds = new HashSet<>();

    // Currently displayed rule in the right panel
    private MobRule currentEdit = null;

    // Tooltip tracking for hovered list item
    private String hoveredTooltipTitle = null;
    private String hoveredTooltipId = null;

    // Widgets
    private EditBox searchBox;

    private int getPanelH() {
        return this.height - HEADER_H - FOOTER_H - PADDING * 2 - PANEL_BOTTOM_GAP;
    }

    // ── Inner data class for a rule ─────────────────────────────────────────

    public static class MobRule {
        public String entityId;
        public double scale = 1.0;
        public double health = 1.0;
        public double damage = 1.0;
        public double speed = 1.0;
        public double follow = 32.0;
        public boolean hostile = false;
        public boolean boats = false;

        public MobRule(String entityId) {
            this.entityId = entityId;
        }

        public MobRule copy() {
            MobRule r = new MobRule(this.entityId);
            r.scale = this.scale;
            r.health = this.health;
            r.damage = this.damage;
            r.speed = this.speed;
            r.follow = this.follow;
            r.hostile = this.hostile;
            r.boats = this.boats;
            return r;
        }

        static MobRule parse(String ruleStr) {
            if (ruleStr == null) return null;
            String[] t = ruleStr.split("\\|");
            if (t.length < 8) return null;

            try {
                MobRule r = new MobRule(t[0].trim());
                // Sanitize ',' to '.' to safely recover from locale-polluted configs
                r.scale   = parseDoubleSafe(t[1]);
                r.health  = parseDoubleSafe(t[2]);
                r.damage  = parseDoubleSafe(t[3]);
                r.speed   = parseDoubleSafe(t[4]);
                r.follow  = parseDoubleSafe(t[5]);
                r.hostile = Boolean.parseBoolean(t[6].trim());
                r.boats   = Boolean.parseBoolean(t[7].trim());
                return r;
            } catch (Exception e) {
                com.serenemobmodifier.SereneMobModifier.LOGGER.error(
                    "[SereneMobModifier] Failed to parse rule '{}': {}", ruleStr, e.getMessage()
                );
                return null; // Return null so corrupted defaults are NOT cached into loadedRules
            }
        }

        private static double parseDoubleSafe(String token) {
            return Double.parseDouble(token.trim().replace(',', '.'));
        }

        public String serialize() {
            return entityId + "|" + fmtD(scale) + "|" + fmtD(health) + "|" + fmtD(damage)
                    + "|" + fmtD(speed) + "|" + fmtD(follow) + "|" + hostile + "|" + boats;
        }

        private static String fmtD(double v) {
            if (v == (long) v) return ((long) v) + ".0";
            // Force US English dot formatting regardless of the player's OS locale
            return String.format(Locale.ROOT, "%.1f", v);
        }
    }

    // ── Constructor ─────────────────────────────────────────────────────────

    public MobModifierScreen() {
        super(Component.literal("Serene Mob Modifier"));
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    public static boolean isLivingMob(EntityType<?> type) {
        if (type == null) return false;
        if (type == EntityType.PLAYER || type == EntityType.ARMOR_STAND) return false;
        if (!DefaultAttributes.hasSupplier(type)) return false;

        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (key == null) return false;

        String path = key.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("contraption") || path.contains("gust") || path.contains("seat")
                || path.contains("projectile") || path.contains("super_glue")
                || path.contains("package") || path.contains("blueprint")) {
            return false;
        }

        if (type.getCategory() == MobCategory.MISC) {
            // Must have supplier (already verified) and no blacklist keywords (already verified)
            return true;
        }

        return true;
    }

    @Override
    protected void init() {
        super.init();

        // Gather all living entity type IDs from the registry
        allEntityIds.clear();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (isLivingMob(type)) {
                ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                if (key != null) {
                    allEntityIds.add(key.toString());
                }
            }
        }
        allEntityIds.sort((id1, id2) -> {
            EntityType<?> t1 = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.tryParse(id1));
            EntityType<?> t2 = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.tryParse(id2));
            String n1 = t1 != null ? t1.getDescription().getString() : id1;
            String n2 = t2 != null ? t2.getDescription().getString() : id2;
            int cmp = n1.compareToIgnoreCase(n2);
            return cmp != 0 ? cmp : id1.compareTo(id2);
        });

        // Load existing rules from config
        loadedRules.clear();
        List<?> configRules = MobModifierConfig.MODIFIER_RULES.get();
        if (configRules != null) {
            for (Object obj : configRules) {
                MobRule r = MobRule.parse(String.valueOf(obj));
                if (r != null) {
                    loadedRules.put(r.entityId, r);
                }
            }
        }

        // Search bar
        int leftPanelX = PADDING;
        int leftPanelW = (this.width / 2) - PADDING * 2;
        searchBox = new EditBox(this.font, leftPanelX + 2, HEADER_H + PADDING + 2, leftPanelW - 4, SEARCH_H,
                Component.literal("Search entities..."));
        searchBox.setMaxLength(128);
        searchBox.setHint(Component.literal("Search entities..."));
        searchBox.setBordered(true);
        searchBox.setResponder(text -> {
            listScrollOffset = 0;
            rightPanelScrollOffset = 0;
            selectedIndex = -1;
            currentEdit = null;
            rebuildFilteredList();
        });
        this.addRenderableWidget(searchBox);

        rebuildFilteredList();
    }

    private void rebuildFilteredList() {
        filteredEntityIds.clear();
        String query = searchBox != null ? searchBox.getValue().trim().toLowerCase(Locale.ROOT) : "";

        for (String id : allEntityIds) {
            if (!matchesTab(id)) continue;
            if (!query.isEmpty()) {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.tryParse(id));
                String displayName = type != null ? type.getDescription().getString().toLowerCase(Locale.ROOT) : "";
                if (!id.toLowerCase(Locale.ROOT).contains(query) && !displayName.contains(query)) {
                    continue;
                }
            }
            filteredEntityIds.add(id);
        }
    }

    private boolean matchesTab(String id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.tryParse(id));
        if (type == null || !isLivingMob(type)) return false;

        return switch (activeTab) {
            case ALL -> true;
            case AQUATIC -> AQUATIC_IDS.contains(id);
            case HOSTILE -> HOSTILE_IDS.contains(id) || type.getCategory() == MobCategory.MONSTER;
            case MODDED -> !id.startsWith("minecraft:");
        };
    }

    private MobRule findRuleForEntity(String entityId) {
        if (pendingEdits.containsKey(entityId)) {
            return pendingEdits.get(entityId);
        }
        if (loadedRules.containsKey(entityId)) {
            return loadedRules.get(entityId);
        }

        // Check wildcard patterns (e.g. "mythsofthesea:*")
        int colonIdx = entityId.indexOf(':');
        if (colonIdx > 0) {
            String namespace = entityId.substring(0, colonIdx);
            for (Map.Entry<String, MobRule> entry : loadedRules.entrySet()) {
                String pattern = entry.getKey();
                if (pattern.endsWith(":*")) {
                    String targetNamespace = pattern.substring(0, pattern.indexOf(":*"));
                    if (namespace.equalsIgnoreCase(targetNamespace)) {
                        return entry.getValue();
                    }
                }
            }
        }
        return null;
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        hoveredTooltipTitle = null;
        hoveredTooltipId = null;

        // 1. Single background pass: blurs the 3D world and applies the dark tint once
        this.renderBackground(gfx, mouseX, mouseY, partialTick);

        // 2. Custom UI drawing: rasterized crisp directly over the blurred world
        renderHeader(gfx, mouseX, mouseY);
        renderLeftPanel(gfx, mouseX, mouseY);
        renderRightPanel(gfx, mouseX, mouseY);
        renderFooter(gfx, mouseX, mouseY);

        // 3. Render registered widgets (searchBox / EditBox) directly
        //    DO NOT call super.render() — it re-invokes renderBackground() and blurs the screen buffer
        for (net.minecraft.client.gui.components.Renderable renderable : this.renderables) {
            renderable.render(gfx, mouseX, mouseY, partialTick);
        }

        // 4. Render tooltip on top of everything
        if (hoveredTooltipTitle != null && hoveredTooltipId != null) {
            ResourceLocation rl = ResourceLocation.tryParse(hoveredTooltipId);
            String ns = rl != null ? rl.getNamespace() : "";
            gfx.renderComponentTooltip(this.font, List.of(
                    Component.literal("§f§l" + hoveredTooltipTitle),
                    Component.literal("§7ID: §8" + hoveredTooltipId),
                    Component.literal("§7Mod: §b" + ns)
            ), mouseX, mouseY);
        }
    }

    private void renderHeader(GuiGraphics gfx, int mouseX, int mouseY) {
        // Header background
        gfx.fill(0, 0, this.width, HEADER_H, COL_HEADER);
        // Bottom accent line
        gfx.fill(0, HEADER_H - 1, this.width, HEADER_H, COL_ACCENT_DIM);

        // Title
        gfx.drawString(this.font, "§l⚙ Serene Mob Modifier", PADDING + 4, 6, COL_ACCENT, false);
        gfx.drawString(this.font, "§7Configure entity attributes and behaviour", PADDING + 4, 18, COL_TEXT_DIM, false);

        // Tabs
        int tabX = PADDING + 4;
        int tabY = HEADER_H - TAB_H - 4;
        for (Tab tab : Tab.values()) {
            int tw = this.font.width(tab.label) + 16;
            boolean isActive = tab == activeTab;
            boolean isHovered = mouseX >= tabX && mouseX < tabX + tw && mouseY >= tabY && mouseY < tabY + TAB_H;

            int bgColor = isActive ? COL_TAB_ACTIVE : (isHovered ? 0xFF2C2C44 : COL_TAB_INACTIVE);
            gfx.fill(tabX, tabY, tabX + tw, tabY + TAB_H, bgColor);
            if (isActive) {
                gfx.fill(tabX, tabY + TAB_H - 2, tabX + tw, tabY + TAB_H, COL_ACCENT);
            }
            gfx.drawCenteredString(this.font, tab.label, tabX + tw / 2, tabY + (TAB_H - 8) / 2, isActive ? COL_TEXT : COL_TEXT_DIM);
            tabX += tw + 3;
        }
    }

    private void renderLeftPanel(GuiGraphics gfx, int mouseX, int mouseY) {
        int panelX = PADDING;
        int panelY = HEADER_H + PADDING;
        int panelW = (this.width / 2) - PADDING * 2;
        int panelH = getPanelH();

        // Panel background
        gfx.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, COL_PANEL_BORDER);
        gfx.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL);

        // List area (below search bar)
        int listY = panelY + SEARCH_H + 8;
        int listH = panelH - SEARCH_H - 10;
        maxVisibleItems = Math.max(1, listH / LIST_ITEM_H);

        // Scrollbar
        int totalItems = filteredEntityIds.size();
        if (totalItems > maxVisibleItems) {
            int scrollTrackX = panelX + panelW - 5;
            int scrollTrackH = listH;
            gfx.fill(scrollTrackX, listY, scrollTrackX + 4, listY + scrollTrackH, 0xFF1A1A28);

            double ratio = (double) maxVisibleItems / totalItems;
            int thumbH = Math.max(10, (int) (scrollTrackH * ratio));
            int thumbY = listY + (int) ((double) listScrollOffset / Math.max(1, totalItems - maxVisibleItems) * (scrollTrackH - thumbH));
            gfx.fill(scrollTrackX, thumbY, scrollTrackX + 4, thumbY + thumbH, COL_ACCENT_DIM);
        }

        // Render items inside scissor box
        gfx.enableScissor(panelX, listY, panelX + panelW, listY + listH);
        for (int i = 0; i < maxVisibleItems && (i + listScrollOffset) < totalItems; i++) {
            int idx = i + listScrollOffset;
            String entityId = filteredEntityIds.get(idx);
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.tryParse(entityId));
            String displayName = type != null ? type.getDescription().getString() : entityId;
            ResourceLocation rl = ResourceLocation.tryParse(entityId);
            String namespace = rl != null ? rl.getNamespace() : "";

            int itemY = listY + i * LIST_ITEM_H;
            int itemW = panelW - 10;
            boolean isSelected = idx == selectedIndex;
            boolean isHovered = mouseX >= panelX + 2 && mouseX < panelX + 2 + itemW
                    && mouseY >= itemY && mouseY < itemY + LIST_ITEM_H
                    && mouseY >= listY && mouseY < listY + listH;
            boolean hasRule = findRuleForEntity(entityId) != null;
            boolean isDirty = dirtyIds.contains(entityId);

            int bg = isSelected ? COL_ITEM_SELECTED : (isHovered ? COL_ITEM_HOVER : COL_ITEM_DEFAULT);
            gfx.fill(panelX + 2, itemY, panelX + 2 + itemW, itemY + LIST_ITEM_H - 1, bg);

            if (isSelected) {
                // Left accent bar for selected item
                gfx.fill(panelX + 2, itemY, panelX + 5, itemY + LIST_ITEM_H - 1, COL_ACCENT);
            }

            // Status indicator dot
            int dotY = itemY + (LIST_ITEM_H - 4) / 2;
            if (isDirty) {
                gfx.fill(panelX + 7, dotY, panelX + 11, dotY + 4, 0xFFFFAA33); // orange = unsaved
            } else if (hasRule) {
                gfx.fill(panelX + 7, dotY, panelX + 11, dotY + 4, COL_TOGGLE_ON); // green = has rule
            }

            // Mod namespace badge on the right
            int badgePadding = 4;
            int badgeTextW = this.font.width(namespace);
            int badgeW = badgeTextW + badgePadding * 2;
            int badgeX = panelX + 2 + itemW - badgeW - 4;
            int badgeY = itemY + (LIST_ITEM_H - 11) / 2;
            int badgeBg = namespace.equals("minecraft") ? 0x40252535 : 0x50354565;
            int badgeTextColor = namespace.equals("minecraft") ? 0xFF888898 : 0xFFAABBFF;

            gfx.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 11, badgeBg);
            gfx.drawString(this.font, namespace, badgeX + badgePadding, badgeY + 2, badgeTextColor, false);

            // Display Name
            int textX = panelX + 14;
            int maxTextW = badgeX - textX - 4;
            String text = displayName;
            if (this.font.width(text) > maxTextW) {
                text = this.font.plainSubstrByWidth(text, Math.max(0, maxTextW - this.font.width("…"))) + "…";
            }
            int nameColor = isSelected ? 0xFFFFFFFF : (hasRule ? COL_ACCENT : COL_TEXT);
            gfx.drawString(this.font, text, textX, itemY + (LIST_ITEM_H - 8) / 2, nameColor, false);

            if (isHovered) {
                hoveredTooltipTitle = displayName;
                hoveredTooltipId = entityId;
            }
        }
        gfx.disableScissor();

        // Item count label: cleanly positioned OUTSIDE and BELOW the scroll list border!
        int countY = panelY + panelH + 4;
        gfx.drawString(this.font, "§7" + totalItems + (totalItems == 1 ? " mob" : " mobs"), panelX + 4, countY, COL_TEXT_DIM, false);
    }

    private void renderRightPanel(GuiGraphics gfx, int mouseX, int mouseY) {
        int panelX = (this.width / 2) + PADDING;
        int panelY = HEADER_H + PADDING;
        int panelW = (this.width / 2) - PADDING * 2;
        int panelH = getPanelH();

        // Panel background
        gfx.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, COL_PANEL_BORDER);
        gfx.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL);

        if (selectedIndex < 0 || selectedIndex >= filteredEntityIds.size()) {
            for (int i = 0; i < sliderBounds.length; i++) sliderBounds[i] = null;
            for (int i = 0; i < toggleBounds.length; i++) toggleBounds[i] = null;
            // No selection — show hint
            String hint1 = "Select an entity from the list";
            String hint2 = "to configure its attributes.";
            int cx = panelX + panelW / 2;
            int cy = panelY + panelH / 2;
            gfx.drawCenteredString(this.font, "§7" + hint1, cx, cy - 10, COL_TEXT_DIM);
            gfx.drawCenteredString(this.font, "§7" + hint2, cx, cy + 4, COL_TEXT_DIM);
            return;
        }

        String entityId = filteredEntityIds.get(selectedIndex);

        if (currentEdit == null || !currentEdit.entityId.equals(entityId)) {
            MobRule existing = findRuleForEntity(entityId);
            if (existing != null) {
                currentEdit = existing.copy();
                currentEdit.entityId = entityId; // Preserve current mob ID if cloned from a wildcard rule
            } else {
                currentEdit = new MobRule(entityId);
            }
        }

        int maxScroll = Math.max(0, rightContentHeight - panelH);
        rightPanelScrollOffset = Mth.clamp(rightPanelScrollOffset, 0, maxScroll);

        int cx = panelX + PADDING;
        int startY = panelY + PADDING - rightPanelScrollOffset;
        int cy = startY;
        int sliderW = panelW - PADDING * 4;

        gfx.enableScissor(panelX, panelY, panelX + panelW, panelY + panelH);

        // ── Entity Title ──
        EntityType<?> selType = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.tryParse(entityId));
        String selName = selType != null ? selType.getDescription().getString() : entityId;
        gfx.drawString(this.font, "§l§f" + selName, cx, cy, COL_TEXT, false);
        cy += 11;
        gfx.drawString(this.font, "§7" + entityId, cx, cy, COL_TEXT_DIM, false);
        cy += 4;

        // Separator
        cy += 12;
        gfx.fill(cx, cy, cx + sliderW, cy + 1, COL_ACCENT_DIM);
        cy += 8;

        // ── Sliders ──
        cy = renderSliderRow(gfx, mouseX, mouseY, "Scale", currentEdit.scale, 0.1, 5.0, 0.1, cx, cy, sliderW, 0);
        cy += 4;
        cy = renderSliderRow(gfx, mouseX, mouseY, "Health", currentEdit.health, 0.5, 10.0, 0.5, cx, cy, sliderW, 1);
        cy += 4;
        cy = renderSliderRow(gfx, mouseX, mouseY, "Damage", currentEdit.damage, 0.5, 10.0, 0.5, cx, cy, sliderW, 2);
        cy += 4;
        cy = renderSliderRow(gfx, mouseX, mouseY, "Speed", currentEdit.speed, 0.5, 3.0, 0.1, cx, cy, sliderW, 3);
        cy += 4;
        cy = renderSliderRow(gfx, mouseX, mouseY, "Follow Range", currentEdit.follow, 16.0, 128.0, 4.0, cx, cy, sliderW, 4);
        cy += 8;

        // Separator
        gfx.fill(cx, cy, cx + sliderW, cy + 1, COL_ACCENT_DIM);
        cy += 8;

        // ── Toggles ──
        cy = renderToggleRow(gfx, mouseX, mouseY, "Hostile to Players", currentEdit.hostile, cx, cy, sliderW, 0);
        cy += 4;
        cy = renderToggleRow(gfx, mouseX, mouseY, "Target Boats", currentEdit.boats, cx, cy, sliderW, 1);

        rightContentHeight = (cy + PADDING) - startY;

        gfx.disableScissor();

        // ── Scrollbar ──
        if (rightContentHeight > panelH) {
            int scrollTrackX = panelX + panelW - 5;
            int scrollTrackH = panelH;
            gfx.fill(scrollTrackX, panelY, scrollTrackX + 4, panelY + scrollTrackH, 0xFF1A1A28);

            double ratio = (double) panelH / rightContentHeight;
            int thumbH = Math.max(10, (int) (scrollTrackH * ratio));
            int thumbY = panelY + (int) ((double) rightPanelScrollOffset / Math.max(1, maxScroll) * (scrollTrackH - thumbH));
            gfx.fill(scrollTrackX, thumbY, scrollTrackX + 4, thumbY + thumbH, COL_ACCENT_DIM);
        }
    }

    /**
     * Renders a slider row with label, track, handle, and live value display.
     * Returns the Y coordinate after the row for layout chaining.
     */
    private int renderSliderRow(GuiGraphics gfx, int mouseX, int mouseY,
                                String label, double value, double min, double max, double step,
                                int x, int y, int w, int sliderIndex) {
        int labelW = 80;
        int valueW = 48;
        int sliderX = x + labelW;
        int sliderW = w - labelW - valueW - 4;
        int sliderH = 12;
        int sliderY = y + 2;

        // Label
        gfx.drawString(this.font, label, x, y + 2, COL_TEXT, false);

        // Track background
        gfx.fill(sliderX, sliderY, sliderX + sliderW, sliderY + sliderH, COL_SLIDER_BG);
        gfx.fill(sliderX, sliderY, sliderX + sliderW, sliderY + 1, 0xFF2A2A3A);
        gfx.fill(sliderX, sliderY + sliderH - 1, sliderX + sliderW, sliderY + sliderH, 0xFF0A0A14);

        // Fill
        double ratio = Mth.clamp((value - min) / (max - min), 0.0, 1.0);
        int fillW = (int) (sliderW * ratio);
        gfx.fill(sliderX, sliderY, sliderX + fillW, sliderY + sliderH, COL_SLIDER_FILL);

        // Handle
        int handleX = sliderX + fillW - 2;
        gfx.fill(handleX, sliderY - 1, handleX + 5, sliderY + sliderH + 1, COL_SLIDER_HANDLE);

        // Value label
        String valStr;
        if (step >= 1.0) {
            valStr = String.format("%.0f", value);
        } else {
            valStr = String.format("%.1f×", value);
        }
        gfx.drawString(this.font, valStr, sliderX + sliderW + 6, y + 2, COL_ACCENT, false);

        // Store slider bounds for click/drag detection
        sliderBounds[sliderIndex] = new int[]{sliderX, sliderY, sliderX + sliderW, sliderY + sliderH};
        sliderMins[sliderIndex] = min;
        sliderMaxs[sliderIndex] = max;
        sliderSteps[sliderIndex] = step;

        return y + sliderH + 6;
    }

    /**
     * Renders a toggle button row. Returns the Y after the row.
     */
    private int renderToggleRow(GuiGraphics gfx, int mouseX, int mouseY,
                                String label, boolean value, int x, int y, int w, int toggleIndex) {
        int toggleW = 36;
        int toggleH = 14;
        int toggleX = x + w - toggleW;
        int toggleY = y;

        // Label
        gfx.drawString(this.font, label, x, y + 3, COL_TEXT, false);

        // Toggle track
        int trackColor = value ? COL_TOGGLE_ON : COL_TOGGLE_OFF;
        gfx.fill(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH, trackColor);
        gfx.fill(toggleX, toggleY, toggleX + toggleW, toggleY + 1, 0x40FFFFFF); // top highlight

        // Toggle handle
        int handleSize = toggleH - 4;
        int handleXPos = value ? (toggleX + toggleW - handleSize - 3) : (toggleX + 3);
        gfx.fill(handleXPos, toggleY + 2, handleXPos + handleSize, toggleY + 2 + handleSize, 0xFFEEEEEE);

        // Status text
        String statusText = value ? "§aON" : "§cOFF";
        gfx.drawString(this.font, statusText, toggleX - this.font.width(value ? "ON" : "OFF") - 6, y + 3, COL_TEXT, false);

        // Store toggle bounds
        toggleBounds[toggleIndex] = new int[]{toggleX, toggleY, toggleX + toggleW, toggleY + toggleH};

        return y + toggleH + 6;
    }

    // Slider interaction state
    private final int[][] sliderBounds = new int[5][4];
    private final double[] sliderMins   = new double[5];
    private final double[] sliderMaxs   = new double[5];
    private final double[] sliderSteps  = new double[5];
    private int draggingSlider = -1;

    // Toggle interaction state
    private final int[][] toggleBounds = new int[2][4];

    private void renderFooter(GuiGraphics gfx, int mouseX, int mouseY) {
        int footerY = this.height - FOOTER_H;
        gfx.fill(0, footerY, this.width, this.height, COL_HEADER);
        gfx.fill(0, footerY, this.width, footerY + 1, COL_ACCENT_DIM);

        // Unsaved changes indicator
        if (!dirtyIds.isEmpty()) {
            gfx.drawString(this.font, "§e⚠ " + dirtyIds.size() + " unsaved change(s)",
                    PADDING + 4, footerY + (FOOTER_H - 8) / 2, 0xFFFFAA33, false);
        }

        // Apply button
        int btnW = 72;
        int btnH = 22;
        int applyX = this.width - btnW * 2 - PADDING * 3;
        int applyY = footerY + (FOOTER_H - btnH) / 2;
        boolean applyHover = mouseX >= applyX && mouseX < applyX + btnW && mouseY >= applyY && mouseY < applyY + btnH;
        gfx.fill(applyX, applyY, applyX + btnW, applyY + btnH, applyHover ? 0xFF4A82D6 : COL_BTN_APPLY);
        gfx.drawCenteredString(this.font, "Apply", applyX + btnW / 2, applyY + (btnH - 8) / 2, 0xFFFFFFFF);
        applyBtnBounds = new int[]{applyX, applyY, applyX + btnW, applyY + btnH};

        // Done button
        int doneX = this.width - btnW - PADDING;
        int doneY = applyY;
        boolean doneHover = mouseX >= doneX && mouseX < doneX + btnW && mouseY >= doneY && mouseY < doneY + btnH;
        gfx.fill(doneX, doneY, doneX + btnW, doneY + btnH, doneHover ? 0xFF3A6A3A : COL_BTN_DONE);
        gfx.drawCenteredString(this.font, "Done", doneX + btnW / 2, doneY + (btnH - 8) / 2, 0xFFFFFFFF);
        doneBtnBounds = new int[]{doneX, doneY, doneX + btnW, doneY + btnH};
    }

    private int[] applyBtnBounds = new int[4];
    private int[] doneBtnBounds  = new int[4];

    // ── Input handling ──────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;

        int mouseX = (int) mx;
        int mouseY = (int) my;

        // ── Tab clicks ──
        if (mouseY >= HEADER_H - TAB_H - 4 && mouseY < HEADER_H - 4) {
            int tabX = PADDING + 4;
            for (Tab tab : Tab.values()) {
                int tw = this.font.width(tab.label) + 16;
                if (mouseX >= tabX && mouseX < tabX + tw) {
                    activeTab = tab;
                    listScrollOffset = 0;
                    rightPanelScrollOffset = 0;
                    selectedIndex = -1;
                    currentEdit = null;
                    rebuildFilteredList();
                    return true;
                }
                tabX += tw + 3;
            }
        }

        // ── Left panel item clicks ──
        int panelX = PADDING;
        int panelW = (this.width / 2) - PADDING * 2;
        int panelH = getPanelH();
        int listY = HEADER_H + PADDING + SEARCH_H + 8;
        int listH = panelH - SEARCH_H - 10;
        if (mouseX >= panelX && mouseX < panelX + panelW && mouseY >= listY && mouseY < listY + listH) {
            int clickedIdx = (mouseY - listY) / LIST_ITEM_H + listScrollOffset;
            if (clickedIdx >= 0 && clickedIdx < filteredEntityIds.size()) {
                if (selectedIndex != clickedIdx) {
                    // Save current edit before switching
                    saveCurrentEditToPending();
                    selectedIndex = clickedIdx;
                    currentEdit = null; // will be loaded from pending/loaded/default on next render
                    rightPanelScrollOffset = 0;
                }
                return true;
            }
        }

        // ── Right panel clicks ──
        int rightPanelX = (this.width / 2) + PADDING;
        int rightPanelW = (this.width / 2) - PADDING * 2;
        int rightPanelY = HEADER_H + PADDING;
        int rightPanelH = getPanelH();

        if (mouseX >= rightPanelX && mouseX < rightPanelX + rightPanelW && mouseY >= rightPanelY && mouseY < rightPanelY + rightPanelH) {
            // Scrollbar click & drag initiation
            if (rightContentHeight > rightPanelH) {
                int scrollTrackX = rightPanelX + rightPanelW - 6;
                if (mouseX >= scrollTrackX) {
                    draggingRightScrollbar = true;
                    double ratio = (double) (mouseY - rightPanelY) / rightPanelH;
                    int maxScroll = rightContentHeight - rightPanelH;
                    rightPanelScrollOffset = Mth.clamp((int) (ratio * maxScroll), 0, maxScroll);
                    return true;
                }
            }

            // ── Slider clicks ──
            for (int i = 0; i < 5; i++) {
                int[] b = sliderBounds[i];
                if (b != null && mouseX >= b[0] && mouseX < b[2] && mouseY >= b[1] && mouseY < b[3]) {
                    draggingSlider = i;
                    updateSliderValue(i, mouseX);
                    return true;
                }
            }

            // ── Toggle clicks ──
            for (int i = 0; i < 2; i++) {
                int[] b = toggleBounds[i];
                if (b != null && mouseX >= b[0] && mouseX < b[2] && mouseY >= b[1] && mouseY < b[3]) {
                    if (currentEdit != null) {
                        if (i == 0) currentEdit.hostile = !currentEdit.hostile;
                        else currentEdit.boats = !currentEdit.boats;
                        markDirty();
                    }
                    return true;
                }
            }
        }

        // ── Footer buttons ──
        if (isInsideBounds(mouseX, mouseY, applyBtnBounds)) {
            applyChanges();
            return true;
        }
        if (isInsideBounds(mouseX, mouseY, doneBtnBounds)) {
            applyChanges();
            this.onClose();
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingSlider >= 0) {
            updateSliderValue(draggingSlider, (int) mx);
            return true;
        }
        if (draggingRightScrollbar) {
            int rightPanelY = HEADER_H + PADDING;
            int rightPanelH = getPanelH();
            if (rightPanelH > 0 && rightContentHeight > rightPanelH) {
                double ratio = (my - rightPanelY) / (double) rightPanelH;
                int maxScroll = rightContentHeight - rightPanelH;
                rightPanelScrollOffset = Mth.clamp((int) (ratio * maxScroll), 0, maxScroll);
                return true;
            }
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingSlider = -1;
        draggingRightScrollbar = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hScroll, double vScroll) {
        int panelY = HEADER_H + PADDING;
        int panelH = getPanelH();

        // Scroll the left panel
        int panelX = PADDING;
        int panelW = (this.width / 2) - PADDING * 2;
        if (mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + panelH) {
            int maxScroll = Math.max(0, filteredEntityIds.size() - maxVisibleItems);
            listScrollOffset = Mth.clamp(listScrollOffset - (int) vScroll, 0, maxScroll);
            return true;
        }

        // Scroll the right panel
        int rightPanelX = (this.width / 2) + PADDING;
        int rightPanelW = (this.width / 2) - PADDING * 2;
        if (mx >= rightPanelX && mx < rightPanelX + rightPanelW && my >= panelY && my < panelY + panelH) {
            int maxScroll = Math.max(0, rightContentHeight - panelH);
            if (maxScroll > 0) {
                int scrollDelta = (int) Math.round(vScroll * 16);
                if (scrollDelta == 0 && vScroll != 0) {
                    scrollDelta = vScroll > 0 ? 1 : -1;
                }
                rightPanelScrollOffset = Mth.clamp(rightPanelScrollOffset - scrollDelta, 0, maxScroll);
                return true;
            }
        }

        return super.mouseScrolled(mx, my, hScroll, vScroll);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Let the search box handle key input first
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── Slider value update logic ───────────────────────────────────────────

    private void updateSliderValue(int index, int mouseX) {
        if (currentEdit == null) return;
        int[] b = sliderBounds[index];
        double ratio = Mth.clamp((double) (mouseX - b[0]) / (b[2] - b[0]), 0.0, 1.0);
        double raw = sliderMins[index] + ratio * (sliderMaxs[index] - sliderMins[index]);
        // Snap to step
        double step = sliderSteps[index];
        double snapped = Math.round(raw / step) * step;
        snapped = Mth.clamp(snapped, sliderMins[index], sliderMaxs[index]);

        switch (index) {
            case 0 -> currentEdit.scale  = snapped;
            case 1 -> currentEdit.health = snapped;
            case 2 -> currentEdit.damage = snapped;
            case 3 -> currentEdit.speed  = snapped;
            case 4 -> currentEdit.follow = snapped;
        }
        markDirty();
    }

    // ── Dirty tracking & persistence ────────────────────────────────────────

    private void markDirty() {
        if (currentEdit != null) {
            dirtyIds.add(currentEdit.entityId);
            pendingEdits.put(currentEdit.entityId, currentEdit.copy());
        }
    }

    private void saveCurrentEditToPending() {
        if (currentEdit != null && dirtyIds.contains(currentEdit.entityId)) {
            pendingEdits.put(currentEdit.entityId, currentEdit.copy());
        }
    }

    /**
     * Applies all pending edits: sends network payloads to the server for each dirty rule.
     */
    private void applyChanges() {
        saveCurrentEditToPending();

        for (String entityId : dirtyIds) {
            MobRule rule = pendingEdits.get(entityId);
            if (rule == null) continue;

            String serialized = rule.serialize();
            // Send to server
            PacketDistributor.sendToServer(new UpdateMobRulePayload(serialized));
            // Update local loaded rules cache
            loadedRules.put(entityId, rule.copy());
        }

        dirtyIds.clear();
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    private boolean isInsideBounds(int mx, int my, int[] bounds) {
        return bounds != null && bounds.length == 4
                && mx >= bounds[0] && mx < bounds[2]
                && my >= bounds[1] && my < bounds[3];
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
