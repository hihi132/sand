package com.example.sandseller;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.util.math.Vec3d;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * /sandseller start | stop
 *
 *  - Baritone dao cat; quai trong tam danh -> tu danh roi cam lai xeng.
 *  - Moi khi hut dung 1 phan thuc an -> tu an.
 *  - Du Config.I.sandLimit cat -> /order, bam o dau tien, nop cat, xac nhan.
 *  - Xeng <= 20% do ben -> (neu het chai EXP) /shop > Totem(Gear) > Chai kinh nghiem > "dat thanh 64" > xac nhan > ESC,
 *    roi nem chai EXP xuong chan, xen ke cam xeng (KHONG dung tay trai, de khong danh nhau voi auto-totem) toi khi >= 80%.
 */
public class SandSellerClient implements ClientModInitializer {
    // ---- CAU HINH ----
    static final int    FIRST_ORDER_SLOT = 0;
    static final int    REPAIR_WAIT     = 12;      // tick cho EXP roi xuong va duoc nhat (xeng dang cam)
    static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
            Items.PUFFERFISH, Items.ENCHANTED_GOLDEN_APPLE, Items.CHORUS_FRUIT);

    enum S { OFF, MINING, FIGHT, EAT,
             OPEN_ORDER, WAIT_LIST, CLICK_FIRST, WAIT_DEPOSIT, DEPOSIT, PRE_CONFIRM, WAIT_CONFIRM, CLICK_CONFIRM, FINISH,
             SHOP_OPEN, SHOP_MAIN, SHOP_GEAR, SHOP_AMOUNT, SHOP_CONFIRM, SHOP_DONE,
             THROW_PREP, THROWING, THROW_RESTORE }

    KeyBinding toggleKey, settingsKey;
    int fightIdle, fightPrevSlot;
    String lastTitle = "", lastSig = "", stableSig = "";
    int stable;
    int sellCooldown, repairCooldown, softFails;
    int depSrc = -1, depTarget = -1, depTries, lastBtn = -1;
    final HashSet<Integer> badSlots = new HashSet<>();
    long startMs; int mined, lastSand = -1, hudTick, dbgTick, confirmTries;
    S state = S.OFF;
    int wait, timeout, retries, orders, lastSync = -1;
    boolean closedOnce;
    // an
    int eatPhase, eatTicks, prevSlot, eatCooldown;
    boolean eatStarted;
    // sua
    int throwCount, shopRetries;
    int shovelHot, bottleHot, bottleSwapInv = -1, throwPhase;
    float fracBeforeThrow = -1;
    int repairNoProgress;

    @Override
    public void onInitializeClient() {
        Config.load();
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.sandseller.toggle",
                InputUtil.Type.KEYSYM, 76 /* L */, KeyBinding.Category.MISC));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.sandseller.settings",
                InputUtil.Type.KEYSYM, 79 /* O */, KeyBinding.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientCommandRegistrationCallback.EVENT.register((d, r) ->
            d.register(ClientCommandManager.literal("sandseller")
                .then(ClientCommandManager.literal("start").executes(c -> { startMining(); return 1; }))
                .then(ClientCommandManager.literal("stop").executes(c -> { stopAll(); return 1; }))));
    }

    boolean isRunning() { return state != S.OFF; }

    void toggle() {
        if (MinecraftClient.getInstance().player == null) return;
        if (state == S.OFF) { startMining(); say("Bat dau"); } else stopAll();
    }

    static String cmd(String c) { return c.startsWith("/") ? c.substring(1) : c; }

    IBaritone baritone() { return BaritoneAPI.getProvider().getPrimaryBaritone(); }

    void startMining() {
        if (state == S.OFF) { startMs = System.currentTimeMillis(); mined = 0; orders = 0; lastSand = -1; }
        state = S.MINING; retries = 0;
        baritone().getMineProcess().mine(Blocks.SAND);
    }

    void stopAll() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) mc.options.useKey.setPressed(false);
        state = S.OFF;
        baritone().getPathingBehavior().cancelEverything();
        say("Da dung");
    }

    /** Loi trong luc ban/mua: KHONG dung bot. Thu lai vai lan, van that bai thi dao tiep va thu lai sau. */
    void soft(MinecraftClient mc, String why, boolean shop) {
        closeAll(mc);
        say("Loi: " + why);
        if (++softFails >= 3) {
            softFails = 0; retries = 0; shopRetries = 0;
            if (shop) repairCooldown = 1200; else sellCooldown = 600;       // 60s / 30s sau thu lai
            say("Bo qua, dao tiep, se thu lai sau");
            startMining();
        } else {
            if (shop) { shopRetries = 0; state = S.SHOP_OPEN; } else { retries = 0; state = S.OPEN_ORDER; }
            wait = 20;
        }
    }

    void skipRepair(String why) {
        say("Loi: " + why + " -> bo qua sua xeng, dao tiep");
        repairCooldown = 2400;
        startMining();
    }

    void fail(String why) { say("Loi: " + why + " -> dung lai"); stopAll(); }

    // ---------- tien ich ----------
    int countSand(ClientPlayerEntity p) { return count(p, Items.SAND); }

    int freeSlots(ClientPlayerEntity p) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getStack(i).isEmpty()) n++;
        return n;
    }

    int count(ClientPlayerEntity p, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) { ItemStack s = p.getInventory().getStack(i); if (s.isOf(item)) n += s.getCount(); }
        return n;
    }

    int firstIndex(ClientPlayerEntity p, Predicate<ItemStack> f) {
        for (int i = 0; i < 36; i++) if (f.test(p.getInventory().getStack(i))) return i;
        return -1;
    }

    int screenSlot(int invIndex) { return invIndex < 9 ? invIndex + 36 : invIndex; }   // man hinh tui do (syncId rieng)

    boolean containerOpen(MinecraftClient mc) {
        return mc.currentScreen instanceof HandledScreen<?> && mc.player.currentScreenHandler != mc.player.playerScreenHandler;
    }
    boolean newScreen(MinecraftClient mc) { return containerOpen(mc) && mc.player.currentScreenHandler.syncId != lastSync; }

    /** Bo dau tieng Viet + chu thuong de so ten khong bi lech (dat thanh == ĐẶT THÀNH). */
    static String norm(String x) {
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return x.replace('đ', 'd').replace('Đ', 'd').toLowerCase(Locale.ROOT).trim();
    }
    String nameOf(ItemStack s) { return norm(s.getName().getString()); }
    String title(MinecraftClient mc) { return mc.currentScreen == null ? "" : norm(mc.currentScreen.getTitle().getString()); }

    /** Cua so da hien du do (server da gui xong item). */
    boolean ready(ClientPlayerEntity p) {
        ScreenHandler h = p.currentScreenHandler;
        for (int i = 0; i < h.slots.size(); i++) {
            Slot sl = h.getSlot(i);
            if (sl.inventory != p.getInventory() && !sl.getStack().isEmpty()) return true;
        }
        return false;
    }
    /** Chu ky noi dung cua so (de nhan ra khi server doi man hinh ngay trong cung mot cua so). */
    String sig(MinecraftClient mc) {
        ScreenHandler h = mc.player.currentScreenHandler;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < h.slots.size(); i++) {
            Slot sl = h.getSlot(i);
            if (sl.inventory == mc.player.getInventory()) continue;
            ItemStack st = sl.getStack();
            b.append(i).append(':').append(st.getItem()).append('x').append(st.getCount()).append(nameOf(st)).append('|');
        }
        return b.toString();
    }
    boolean changed(MinecraftClient mc) {
        return mc.player.currentScreenHandler.syncId != lastSync || !title(mc).equals(lastTitle) || !sig(mc).equals(lastSig);
    }
    void mark(MinecraftClient mc) { lastSync = mc.player.currentScreenHandler.syncId; lastTitle = title(mc); lastSig = containerOpen(mc) ? sig(mc) : ""; stableSig = ""; stable = 0; }

    /** Man hinh da dung yen (noi dung khong doi) du settleTicks tick -> an toan de bam. Chong bam nham khi server lag/sap xep lai. */
    boolean settled(MinecraftClient mc) {
        String sg = sig(mc);
        if (sg.equals(stableSig)) stable++; else { stableSig = sg; stable = 0; }
        return stable >= Config.I.settleTicks;
    }
    void closeAll(MinecraftClient mc) {                                                  // = bam ESC
        if (mc.player != null) mc.player.closeHandledScreen();
        if (mc.currentScreen != null) mc.setScreen(null);
    }
    void dbg(String m) { if (Config.I.debug) say("[dbg] " + m); }

    /** Tim o trong cua so dang mo (khong tinh tui do nguoi choi). */
    int findContainerSlot(ClientPlayerEntity p, Predicate<ItemStack> f) {
        ScreenHandler h = p.currentScreenHandler;
        for (int i = 0; i < h.slots.size(); i++) {
            Slot s = h.getSlot(i);
            if (s.inventory == p.getInventory()) continue;
            if (!s.getStack().isEmpty() && f.test(s.getStack())) return i;
        }
        return -1;
    }

    String dump(ClientPlayerEntity p) {
        StringBuilder b = new StringBuilder(); ScreenHandler h = p.currentScreenHandler;
        for (int i = 0; i < h.slots.size(); i++) {
            Slot s = h.getSlot(i);
            if (s.inventory == p.getInventory() || s.getStack().isEmpty()) continue;
            b.append(i).append("=").append(nameOf(s.getStack())).append("; ");
        }
        return b.length() > 400 ? b.substring(0, 400) + "..." : b.toString();
    }

    void clickContainer(MinecraftClient mc, int slot) {
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }

    // ---------- an ----------
    int findFood(ClientPlayerEntity p) {
        return firstIndex(p, s -> !s.isEmpty() && s.get(DataComponentTypes.FOOD) != null && !BAD_FOOD.contains(s.getItem()));
    }

    boolean needEat(ClientPlayerEntity p) {
        if (!Config.I.autoEat) return false;
        int fi = findFood(p);
        if (fi < 0) return false;
        FoodComponent fc = p.getInventory().getStack(fi).get(DataComponentTypes.FOOD);
        int level = p.getHungerManager().getFoodLevel();
        return level < 20 && level <= 20 - fc.nutrition();    // hut du 1 phan thuc an thi an
    }

    // ---------- sua ----------
    int findShovel(ClientPlayerEntity p) {
        return firstIndex(p, s -> s.isIn(ItemTags.SHOVELS) && s.getMaxDamage() > 0);
    }
    float shovelFrac(ItemStack s) { return 1f - (float) s.getDamage() / s.getMaxDamage(); }

    boolean needRepair(ClientPlayerEntity p) {
        if (!Config.I.autoRepair) return false;
        int si = findShovel(p);
        return si >= 0 && shovelFrac(p.getInventory().getStack(si)) <= (Config.I.repairStart / 100f);
    }

    /** Nut "dat thanh 64": uu tien ten co 'dat thanh 64', neu khong thi o nao ten co '64' (khong phai chai EXP). */
    int find64(ClientPlayerEntity p) {
        int t = findContainerSlot(p, st -> { String n = nameOf(st); return n.contains("dat thanh 64") || n.contains("set to 64") || n.contains("set 64"); });
        if (t < 0) t = posSlot(p, 4);                      // o xanh nhat ben phai cung, cung hang voi chai
        if (t < 0) t = findContainerSlot(p, st -> !st.isOf(Items.EXPERIENCE_BOTTLE) && nameOf(st).contains("64"));
        return t;
    }

    int findConfirm(ClientPlayerEntity p) {
        int t = findContainerSlot(p, st -> { String n = nameOf(st); return n.contains("xac nhan") || n.contains("confirm"); });
        if (t < 0) t = posSlot(p, 10);                     // o xanh duoi chai, lech phai 1 cot (canh nut do)
        return t;
    }

    /** O nam cach chai EXP "delta" slot (khong rong). Chi dung khi khong tim duoc theo ten. */
    int posSlot(ClientPlayerEntity p, int delta) {
        int b = findContainerSlot(p, st -> st.isOf(Items.EXPERIENCE_BOTTLE));
        if (b < 0) return -1;
        ScreenHandler h = p.currentScreenHandler;
        int idx = b + delta, containerSize = h.slots.size() - 36;
        if (idx < 0 || idx >= containerSize || h.getSlot(idx).getStack().isEmpty()) return -1;
        return idx;
    }

    /** Nut giao/xac nhan: theo ten, neu khong co thi o kinh/block mau xanh la (lime/green/emerald). */
    int findButton(ClientPlayerEntity p) {
        int t = findContainerSlot(p, st -> { String n = nameOf(st);
            return n.contains("xac nhan") || n.contains("confirm") || n.contains("giao") || n.contains("deliver")
                || n.contains("submit") || n.contains("accept") || n.contains("dong y") || n.contains("hoan tat"); });
        if (t >= 0) return t;
        ScreenHandler h = p.currentScreenHandler;
        for (int i = h.slots.size() - 37; i >= 0; i--) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            String id = Registries.ITEM.getId(st.getItem()).getPath();
            if (id.contains("lime") || id.contains("green") || st.isOf(Items.EMERALD_BLOCK)) return i;
        }
        return -1;
    }

    /** O khong rong va khong phai cat o khu vuc duoi cung cua cua so = nut xac nhan. */
    int buttonFallback(ClientPlayerEntity p) {
        ScreenHandler h = p.currentScreenHandler;
        for (int i = h.slots.size() - 37; i >= 0; i--) {
            ItemStack st = h.getSlot(i).getStack();
            if (!st.isEmpty() && !st.isOf(Items.SAND)) return i;
        }
        return -1;
    }

    // ---------- thong tin hien tren man hinh (giong HUD cua AutoSand) ----------
    void updateStats(ClientPlayerEntity p) {
        if (state != S.MINING) return;
        int now = countSand(p);
        if (lastSand >= 0 && now > lastSand) mined += now - lastSand;
        lastSand = now;
    }

    String stateLabel() {
        return switch (state) {
            case MINING -> "Đào";
            case FIGHT -> "Đánh quái";
            case EAT -> "Ăn";
            case OPEN_ORDER, WAIT_LIST, CLICK_FIRST, WAIT_DEPOSIT, DEPOSIT, PRE_CONFIRM, WAIT_CONFIRM, CLICK_CONFIRM, FINISH -> "Bán";
            case SHOP_OPEN, SHOP_MAIN, SHOP_GEAR, SHOP_AMOUNT, SHOP_CONFIRM, SHOP_DONE -> "Mua EXP";
            case THROW_PREP, THROWING, THROW_RESTORE -> "Sửa xẻng";
            default -> "Chờ";
        };
    }

    String hud(ClientPlayerEntity p) {
        int si = findShovel(p);
        String shovel = "--";
        if (si >= 0) {
            ItemStack st = p.getInventory().getStack(si);
            shovel = Math.round(shovelFrac(st) * 100) + "% (" + (st.getMaxDamage() - st.getDamage()) + "/" + st.getMaxDamage() + ")";
        }
        long sec = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("§aSandSeller §7[§e%s§7] §fXẻng §e%s §7| §fĂn §e%d/20 §7| §fTúi §e%d/36 §7| §fCát §e%d §7| §fEXP §e%d §7| §fĐã đào §e%d §7| §fOrder §e%d §7| §e%02d:%02d:%02d",
                stateLabel(), shovel, p.getHungerManager().getFoodLevel(), 36 - freeSlots(p), countSand(p),
                count(p, Items.EXPERIENCE_BOTTLE), mined, orders, sec / 3600, (sec / 60) % 60, sec % 60);
    }

    // ---------- chon don hang gia cao nhat ----------
    static final java.util.regex.Pattern MONEY = java.util.regex.Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?");
    static final java.util.regex.Pattern NUM = java.util.regex.Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?");

    static double scale(String num, String sfx) {
        double v = Double.parseDouble(num.replace(",", ""));
        if (sfx != null) switch (Character.toLowerCase(sfx.charAt(0))) { case 'k' -> v *= 1e3; case 'm' -> v *= 1e6; case 'b' -> v *= 1e9; default -> { } }
        return v;
    }

    /** Doc gia/1 cat tu lore cua o don hang (uu tien dong co chu price/each/per/gia). -1 neu khong doc duoc. */
    double price(ItemStack st) {
        var lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        double fallback = -1;
        for (Text line : lore.lines()) {
            String t = line.getString(), n = norm(t);
            boolean key = n.contains("price") || n.contains("each") || n.contains("per") || n.contains("gia") || n.contains("/");
            try {
                var m = MONEY.matcher(t);
                while (m.find()) { double v = scale(m.group(1), m.group(2)); if (key) return v; if (fallback < 0) fallback = v; }
                if (key) { var m2 = NUM.matcher(t); if (m2.find()) return scale(m2.group(1), m2.group(2)); }
            } catch (Exception ignored) { }
        }
        return fallback;
    }

    /** Chon o don CAT co gia cao nhat; khong doc duoc gia thi lay o dau tien. */
    int bestOrderSlot(ClientPlayerEntity p) {
        ScreenHandler h = p.currentScreenHandler;
        int size = h.slots.size() - 36, best = -1;
        double bp = -1;
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < size; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (!st.isOf(Items.SAND)) continue;
            double pr = price(st);
            if (log.length() < 150) log.append(i).append("=").append(pr).append(" ");
            if (pr > bp) { bp = pr; best = i; }
        }
        dbg("Gia cac don cat: " + log);
        if (best < 0) {
            ItemStack f = h.getSlot(FIRST_ORDER_SLOT).getStack();
            var lore = f.get(DataComponentTypes.LORE);
            if (lore != null) { StringBuilder b = new StringBuilder(); for (Text l : lore.lines()) b.append(l.getString()).append(" | "); dbg("Lore o dau: " + b); }
            return FIRST_ORDER_SLOT;
        }
        return best;
    }

    // ---------- danh quai ----------
    HostileEntity nearestMob(MinecraftClient mc, ClientPlayerEntity p, double range) {
        if (mc.world == null) return null;
        List<HostileEntity> list = mc.world.getEntitiesByClass(HostileEntity.class, p.getBoundingBox().expand(range + 1.0),
                e -> e.isAlive() && !(e instanceof EndermanEntity) && !(e instanceof ZombifiedPiglinEntity) && p.canSee(e));
        HostileEntity best = null; double bd = Double.MAX_VALUE;
        for (HostileEntity e : list) {
            double d = p.distanceTo(e);
            if (d <= range && d < bd) { bd = d; best = e; }
        }
        return best;
    }

    void aimAt(ClientPlayerEntity p, Entity e) {
        Vec3d from = p.getEyePos(), to = e.getEyePos();
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setPitch((float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    int findWeapon(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getStack(i).isIn(ItemTags.SWORDS)) return i;
        for (int i = 0; i < 9; i++) if (p.getInventory().getStack(i).isIn(ItemTags.AXES)) return i;
        return -1;
    }

    // ---------- tick ----------
    void tick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) toggle();                                    // phim L: bat/tat
        while (settingsKey.wasPressed()) mc.setScreen(new SettingsScreen(this));     // phim O: mo menu
        ClientPlayerEntity p = mc.player;
        if (p == null || state == S.OFF) return;
        if (eatCooldown > 0) eatCooldown--;
        if (sellCooldown > 0) sellCooldown--;
        if (repairCooldown > 0) repairCooldown--;
        updateStats(p);
        if (Config.I.hud && ++hudTick >= 10) { hudTick = 0; p.sendMessage(Text.literal(hud(p)), true); }
        if (wait > 0) { wait--; return; }

        switch (state) {
            case MINING -> {
                if (Config.I.attackMobs && nearestMob(mc, p, Config.I.attackRange) != null) {
                    baritone().getPathingBehavior().cancelEverything();
                    fightPrevSlot = p.getInventory().getSelectedSlot(); fightIdle = 0;
                    state = S.FIGHT; return;
                }
                if (eatCooldown == 0 && needEat(p)) { baritone().getPathingBehavior().cancelEverything(); eatPhase = 0; state = S.EAT; return; }
                boolean hasBottles = count(p, Items.EXPERIENCE_BOTTLE) > 0;
                boolean full = freeSlots(p) == 0;
                boolean repairNeeded = repairCooldown == 0 && needRepair(p);
                if (repairCooldown > 0 && ++dbgTick % 100 == 0 && needRepair(p))
                    dbg("Xeng thap nhung dang cho thu lai sua (" + (repairCooldown / 20) + "s)");
                // 1) Xeng sap hong va con chai EXP -> sua truoc (khong can o trong)
                if (repairNeeded && hasBottles) {
                    baritone().getPathingBehavior().cancelEverything();
                    say("Xeng sap hong, ne chai EXP de sua");
                    fracBeforeThrow = shovelFrac(p.getInventory().getStack(findShovel(p)));
                    state = S.THROW_PREP; return;
                }
                // 2) Du cat HOAC tui day (khong nhat them duoc) -> di ban
                int sand = countSand(p);
                if (sellCooldown == 0 && sand > 0 && (sand >= Config.I.sandLimit || full)) {
                    baritone().getPathingBehavior().cancelEverything();
                    say((full ? "Tui day, " : "Du " + Config.I.sandLimit + " cat, ") + "di ban");
                    state = S.OPEN_ORDER; return;
                }
                // 3) Xeng sap hong, het chai -> ra shop mua (can o trong de nhan chai)
                if (repairNeeded && !hasBottles && !full) {
                    baritone().getPathingBehavior().cancelEverything();
                    say("Xeng sap hong, di mua chai EXP");
                    fracBeforeThrow = shovelFrac(p.getInventory().getStack(findShovel(p)));
                    shopRetries = 0; state = S.SHOP_OPEN; return;
                }
                if (!baritone().getMineProcess().isActive()) baritone().getMineProcess().mine(Blocks.SAND);
            }

            // ===== DANH QUAI =====
            case FIGHT -> {
                HostileEntity t = Config.I.attackMobs ? nearestMob(mc, p, Config.I.attackRange + 0.5) : null;
                if (t == null) {
                    if (++fightIdle > 10) {                         // het quai -> cam lai xeng, dao tiep
                        int si = findShovel(p);
                        p.getInventory().setSelectedSlot((si >= 0 && si <= 8) ? si : fightPrevSlot);
                        fightIdle = 0; wait = 2; startMining();
                    }
                    return;
                }
                fightIdle = 0;
                int wi = findWeapon(p);
                if (wi >= 0 && p.getInventory().getSelectedSlot() != wi) { p.getInventory().setSelectedSlot(wi); wait = 2; return; }
                aimAt(p, t);
                if (p.getAttackCooldownProgress(0.5f) >= 0.95f) {
                    mc.interactionManager.attackEntity(p, t);
                    p.swingHand(Hand.MAIN_HAND);
                }
            }

            // ===== AN =====
            case EAT -> {
                if (eatPhase == 0) {
                    int fi = findFood(p);
                    if (fi < 0) { eatCooldown = 200; startMining(); return; }
                    prevSlot = p.getInventory().getSelectedSlot();
                    int hot = fi;
                    if (fi >= 9) { mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, fi, 8, SlotActionType.SWAP, p); hot = 8; }
                    p.getInventory().setSelectedSlot(hot);
                    eatPhase = 1; eatTicks = 0; eatStarted = false; wait = 3;
                } else {
                    eatTicks++;
                    mc.options.useKey.setPressed(true);
                    if (p.isUsingItem()) eatStarted = true;
                    if ((eatStarted && !p.isUsingItem()) || eatTicks > 80) {
                        mc.options.useKey.setPressed(false);
                        p.getInventory().setSelectedSlot(prevSlot);
                        eatPhase = 0; eatCooldown = 40;
                        startMining();
                    }
                }
            }

            // ===== BAN CAT (/order) =====
            case OPEN_ORDER -> {
                if (++retries > 3) { soft(mc, "khong mo duoc lenh ban", false); return; }
                mark(mc);
                p.networkHandler.sendChatCommand(cmd(Config.I.sellCommand));
                timeout = 0; state = S.WAIT_LIST; wait = 10;
            }
            case WAIT_LIST -> {
                if (containerOpen(mc) && ready(p) && settled(mc)) {
                    dbg("Order mo: '" + title(mc) + "'");
                    mark(mc); state = S.CLICK_FIRST; wait = 1;
                } else if (++timeout > 60) state = S.OPEN_ORDER;
            }
            case CLICK_FIRST -> {
                int pick = bestOrderSlot(p);
                dbg("Chon don slot " + pick + " gia " + price(p.currentScreenHandler.getSlot(pick).getStack()));
                mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, pick, 0, SlotActionType.PICKUP, p);
                timeout = 0; state = S.WAIT_DEPOSIT; wait = 6;
            }
            case WAIT_DEPOSIT -> {
                if (containerOpen(mc) && ready(p) && changed(mc) && settled(mc)) {
                    dbg("Man nop mo: '" + title(mc) + "'");
                    mark(mc); badSlots.clear(); depSrc = -1; depTarget = -1; depTries = 0;
                    state = S.DEPOSIT; wait = 1;
                } else if (++timeout > 60) { dbg("Khong thay man nop, thu lai"); closeAll(mc); state = S.OPEN_ORDER; }
            }
            // Man "BO VAT PHAM VAO DE GIAO": phai nhat chong cat len chuot roi bam vao o trong de dat xuong (shift-click khong dung duoc)
            case DEPOSIT -> {
                ScreenHandler h = p.currentScreenHandler;
                int cs = h.slots.size() - 36;
                if (!h.getCursorStack().isEmpty()) {                          // dang cam cat tren chuot -> dat vao o trong
                    int target = -1;
                    for (int i = 0; i < Math.max(1, cs - 9); i++) if (h.getSlot(i).getStack().isEmpty()) { target = i; break; }
                    if (target < 0) { dbg("Het cho de dat cat"); mark(mc); state = S.PRE_CONFIRM; wait = 3; return; }
                    mc.interactionManager.clickSlot(h.syncId, target, 0, SlotActionType.PICKUP, p);
                    wait = 3; return;
                }
                int src = -1;                                                   // chuot trong -> nhat 1 chong cat tu tui
                for (int i = 0; i < h.slots.size(); i++) {
                    Slot sl = h.getSlot(i);
                    if (sl.inventory == p.getInventory() && sl.getStack().isOf(Items.SAND)) { src = i; break; }
                }
                if (src < 0) { dbg("Da dat xong cat"); mark(mc); state = S.PRE_CONFIRM; wait = 3; return; }
                mc.interactionManager.clickSlot(h.syncId, src, 0, SlotActionType.PICKUP, p);
                wait = 3;
            }
            case PRE_CONFIRM -> {                                               // doi nut chuyen sang xanh roi moi bam
                if (!containerOpen(mc)) { state = S.FINISH; return; }
                if (settled(mc)) { confirmTries = 0; state = S.CLICK_CONFIRM; }
            }
            case CLICK_CONFIRM -> {
                dbg("Slot: " + dump(p));
                int t = findContainerSlot(p, st -> { String n = nameOf(st);
                    return n.contains("xac nhan") || n.contains("confirm") || n.contains("accept") || n.contains("dong y")
                        || n.contains("deliver") || n.contains("giao hang")
                        || st.isOf(Items.LIME_STAINED_GLASS_PANE) || st.isOf(Items.GREEN_STAINED_GLASS_PANE) || st.isOf(Items.EMERALD_BLOCK); });
                if (t < 0 && confirmTries == 0) t = buttonFallback(p);          // o duy nhat khong phai cat (nut o hang duoi)
                if (t < 0) {
                    if (confirmTries == 0) soft(mc, "khong tim thay nut xac nhan (xem dong Slot o tren)", false);
                    else state = S.FINISH;
                    return;
                }
                dbg("Bam nut xac nhan o slot " + t);
                mark(mc); clickContainer(mc, t); confirmTries++;
                timeout = 0; state = S.WAIT_CONFIRM; wait = 12;
            }
            case WAIT_CONFIRM -> {
                timeout++;
                if (!containerOpen(mc)) { state = S.FINISH; return; }           // server da dong -> xong
                if (confirmTries < 3 && changed(mc) && settled(mc)) { dbg("Hien man xac nhan tiep: '" + title(mc) + "'"); state = S.CLICK_CONFIRM; return; }
                if (timeout > 60) { dbg("Het gio cho -> dong cua so (ESC)"); state = S.FINISH; }
            }
            case FINISH -> {
                closeAll(mc);                                                         // ESC
                orders++; softFails = 0;
                say("Ban xong lan " + orders + ", con " + countSand(p) + " cat");
                retries = 0;
                if (countSand(p) >= Config.I.sandLimit) state = S.OPEN_ORDER; else startMining();
            }

            // ===== MUA CHAI EXP (/shop) =====
            case SHOP_OPEN -> {
                if (++shopRetries > 3) { soft(mc, "khong mo duoc lenh shop", true); return; }
                mark(mc);
                p.networkHandler.sendChatCommand(cmd(Config.I.shopCommand));
                timeout = 0; state = S.SHOP_MAIN; wait = 10;
            }
            case SHOP_MAIN -> {
                if (containerOpen(mc) && ready(p)) {
                    dbg("Shop: '" + title(mc) + "' " + dump(p));
                    int t = findContainerSlot(p, st -> st.isOf(Items.TOTEM_OF_UNDYING) || nameOf(st).contains("gear"));        // muc Gear
                    if (t < 0) { soft(mc, "khong thay Totem trong /shop", true); return; }
                    mark(mc); clickContainer(mc, t); timeout = 0; state = S.SHOP_GEAR; wait = 6;
                } else if (++timeout > 60) state = S.SHOP_OPEN;
            }
            case SHOP_GEAR -> {
                if (containerOpen(mc) && ready(p) && changed(mc)) {
                    dbg("Gear: '" + title(mc) + "' " + dump(p));
                    int t = findContainerSlot(p, st -> st.isOf(Items.EXPERIENCE_BOTTLE) || nameOf(st).contains("kinh nghiem") || nameOf(st).contains("experience"));
                    if (t < 0) { soft(mc, "khong thay chai EXP trong Gear", true); return; }
                    mark(mc); clickContainer(mc, t); timeout = 0; state = S.SHOP_AMOUNT; wait = 6;
                } else if (++timeout > 60) { closeAll(mc); state = S.SHOP_OPEN; }
            }
            case SHOP_AMOUNT -> {
                if (containerOpen(mc) && ready(p) && changed(mc)) {
                    dbg("Mua EXP: '" + title(mc) + "' " + dump(p));
                    int t = find64(p);
                    if (t < 0) { soft(mc, "khong thay nut 'dat thanh 64' (xem dong Mua EXP o tren)", true); return; }
                    mark(mc); clickContainer(mc, t); dbg("Da bam dat thanh 64");
                    timeout = 0; state = S.SHOP_CONFIRM; wait = 8;
                } else if (++timeout > 60) { closeAll(mc); state = S.SHOP_OPEN; }
            }
            case SHOP_CONFIRM -> {
                timeout++;
                boolean is64 = findContainerSlot(p, st -> st.isOf(Items.EXPERIENCE_BOTTLE) && st.getCount() == 64) >= 0;
                if (is64) {
                    int t = findConfirm(p);
                    if (t >= 0) { dbg("Bam xac nhan mua"); clickContainer(mc, t); state = S.SHOP_DONE; wait = 15; return; }
                } else if (timeout == 30) {                                                    // chua len 64 -> bam lai
                    int t = find64(p);
                    if (t >= 0) { dbg("Chua len 64, bam lai"); clickContainer(mc, t); }
                }
                if (timeout % 25 == 0) dbg("Dang doi... " + dump(p));
                if (timeout > 100) soft(mc, "khong bam duoc xac nhan mua (xem dong Dang doi o tren)", true);
            }
            case SHOP_DONE -> {
                closeAll(mc);                                                                  // ESC
                if (count(p, Items.EXPERIENCE_BOTTLE) > 0) { softFails = 0; say("Da mua chai EXP"); state = S.THROW_PREP; wait = 5; }
                else soft(mc, "mua xong nhung khong thay chai EXP (het tien?)", true);
            }

            // ===== NEM CHAI EXP DE SUA (KHONG DUNG TAY TRAI) =====
            // Xeng nam trong thanh hotbar. Moi vong: chon chai -> nem -> chon lai xeng (cam tay chinh)
            // -> cho vai tick de cuc EXP roi xuong chan va Mending sua xeng dang cam.
            case THROW_PREP -> {
                int bi = firstIndex(p, s -> s.isOf(Items.EXPERIENCE_BOTTLE));
                int si = findShovel(p);
                if (bi < 0) { startMining(); return; }
                if (si < 0 || si > 8) { skipRepair("xeng phai nam tren thanh hotbar (o 1-9)"); return; }
                shovelHot = si; bottleSwapInv = -1;
                if (bi < 9) bottleHot = bi;
                else {
                    int hot = (si == 8) ? 7 : 8;
                    mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, bi, hot, SlotActionType.SWAP, p);
                    bottleHot = hot; bottleSwapInv = bi;
                }
                throwCount = 0; throwPhase = 0; state = S.THROWING; wait = 4;
            }
            case THROWING -> {
                ItemStack shovel = p.getInventory().getStack(shovelHot);
                boolean done = count(p, Items.EXPERIENCE_BOTTLE) == 0 || throwCount > 200
                        || !shovel.isIn(ItemTags.SHOVELS) || shovelFrac(shovel) >= (Config.I.repairStop / 100f);
                if (throwPhase == 0) {
                    if (done) { state = S.THROW_RESTORE; return; }
                    if (!p.getInventory().getStack(bottleHot).isOf(Items.EXPERIENCE_BOTTLE)) { state = S.THROW_PREP; return; }
                    p.getInventory().setSelectedSlot(bottleHot);
                    p.setPitch(90f);
                    mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
                    throwCount++; throwPhase = 1; wait = 1;
                } else {
                    p.getInventory().setSelectedSlot(shovelHot);      // cam xeng de nhan EXP
                    throwPhase = 0; wait = REPAIR_WAIT;
                }
            }
            case THROW_RESTORE -> {
                p.getInventory().setSelectedSlot(shovelHot);
                if (bottleSwapInv >= 0)   // tra do ve cho cu
                    mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, screenSlot(bottleSwapInv), bottleHot, SlotActionType.SWAP, p);
                int si = findShovel(p);
                float now = si >= 0 ? shovelFrac(p.getInventory().getStack(si)) : 1f;
                say("Xeng: " + Math.round(now * 100) + "%");
                if (now <= fracBeforeThrow + 0.01f) { if (++repairNoProgress >= 2) { repairNoProgress = 0; skipRepair("xeng khong len do ben (co Mending khong?)"); return; } }
                else repairNoProgress = 0;
                wait = 10; startMining();
            }
            default -> {}
        }
    }

    void say(String m) {
        var p = MinecraftClient.getInstance().player;
        if (p != null) p.sendMessage(Text.literal("[SandSeller] " + m), false);
    }
}
