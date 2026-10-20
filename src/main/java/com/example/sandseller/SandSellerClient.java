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
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.util.math.Vec3d;
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
             OPEN_ORDER, WAIT_LIST, CLICK_FIRST, WAIT_DEPOSIT, DEPOSIT, WAIT_CONFIRM, CLICK_CONFIRM, FINISH,
             SHOP_OPEN, SHOP_MAIN, SHOP_GEAR, SHOP_AMOUNT, SHOP_CONFIRM, SHOP_DONE,
             THROW_PREP, THROWING, THROW_RESTORE }

    KeyBinding toggleKey, settingsKey;
    int fightIdle, fightPrevSlot;
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

    void fail(String why) { say("Loi: " + why + " -> dung lai"); stopAll(); }

    // ---------- tien ich ----------
    int countSand(ClientPlayerEntity p) { return count(p, Items.SAND); }

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

    String nameOf(ItemStack s) { return s.getName().getString().toLowerCase(Locale.ROOT); }

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
        return b.toString();
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
        if (wait > 0) { wait--; return; }

        switch (state) {
            case MINING -> {
                if (Config.I.attackMobs && nearestMob(mc, p, Config.I.attackRange) != null) {
                    baritone().getPathingBehavior().cancelEverything();
                    fightPrevSlot = p.getInventory().getSelectedSlot(); fightIdle = 0;
                    state = S.FIGHT; return;
                }
                if (eatCooldown == 0 && needEat(p)) { baritone().getPathingBehavior().cancelEverything(); eatPhase = 0; state = S.EAT; return; }
                if (countSand(p) >= Config.I.sandLimit) {
                    baritone().getPathingBehavior().cancelEverything();
                    say("Du " + Config.I.sandLimit + " cat, di ban");
                    state = S.OPEN_ORDER; return;
                }
                if (needRepair(p)) {
                    baritone().getPathingBehavior().cancelEverything();
                    say("Xeng sap hong, di sua");
                    fracBeforeThrow = shovelFrac(p.getInventory().getStack(findShovel(p)));
                    if (count(p, Items.EXPERIENCE_BOTTLE) > 0) state = S.THROW_PREP;
                    else { shopRetries = 0; state = S.SHOP_OPEN; }
                    return;
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
                if (++retries > 3) { fail("khong mo duoc lenh ban"); return; }
                lastSync = p.currentScreenHandler.syncId;
                p.networkHandler.sendChatCommand(cmd(Config.I.sellCommand));
                timeout = 0; state = S.WAIT_LIST; wait = 10;
            }
            case WAIT_LIST -> {
                if (newScreen(mc)) { lastSync = p.currentScreenHandler.syncId; state = S.CLICK_FIRST; wait = 8; }
                else if (++timeout > 8) state = S.OPEN_ORDER;
            }
            case CLICK_FIRST -> {
                mc.interactionManager.clickSlot(lastSync, FIRST_ORDER_SLOT, 0, SlotActionType.PICKUP, p);
                timeout = 0; state = S.WAIT_DEPOSIT; wait = 10;
            }
            case WAIT_DEPOSIT -> {
                if (newScreen(mc)) { lastSync = p.currentScreenHandler.syncId; state = S.DEPOSIT; wait = 8; }
                else if (++timeout > 8) { p.closeHandledScreen(); state = S.OPEN_ORDER; }
            }
            case DEPOSIT -> {
                ScreenHandler h = p.currentScreenHandler;
                int clicks = 0;
                for (int i = 0; i < h.slots.size() && clicks < 3; i++) {
                    Slot s = h.getSlot(i);
                    if (s.inventory == p.getInventory() && s.getStack().isOf(Items.SAND)) {
                        mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, p);
                        clicks++;
                    }
                }
                if (clicks == 0) { timeout = 0; closedOnce = false; state = S.WAIT_CONFIRM; wait = 10; }
                else wait = 2;
            }
            case WAIT_CONFIRM -> {
                if (newScreen(mc)) { lastSync = p.currentScreenHandler.syncId; state = S.CLICK_CONFIRM; wait = 8; return; }
                timeout++;
                if (timeout == 15 && containerOpen(mc) && !closedOnce) { closedOnce = true; p.closeHandledScreen(); }
                else if (timeout > 50) state = S.FINISH;
            }
            case CLICK_CONFIRM -> {
                int t = findContainerSlot(p, s -> { String n = nameOf(s);
                    return n.contains("xác nhận") || n.contains("confirm") || n.contains("accept") || n.contains("đồng ý")
                        || s.isOf(Items.LIME_STAINED_GLASS_PANE) || s.isOf(Items.EMERALD_BLOCK); });
                if (t < 0) { say("Khong thay nut xac nhan. Cac slot: " + dump(p)); fail("khong tim thay nut xac nhan"); return; }
                clickContainer(mc, t);
                state = S.FINISH; wait = 20;
            }
            case FINISH -> {
                if (containerOpen(mc)) p.closeHandledScreen();
                orders++;
                say("Ban xong lan " + orders + ", con " + countSand(p) + " cat");
                retries = 0;
                if (countSand(p) >= Config.I.sandLimit) state = S.OPEN_ORDER; else startMining();
            }

            // ===== MUA CHAI EXP (/shop) =====
            case SHOP_OPEN -> {
                if (++shopRetries > 3) { fail("khong mo duoc lenh shop"); return; }
                lastSync = p.currentScreenHandler.syncId;
                p.networkHandler.sendChatCommand(cmd(Config.I.shopCommand));
                timeout = 0; state = S.SHOP_MAIN; wait = 10;
            }
            case SHOP_MAIN -> {
                if (newScreen(mc)) {
                    lastSync = p.currentScreenHandler.syncId;
                    int t = findContainerSlot(p, s -> s.isOf(Items.TOTEM_OF_UNDYING));       // muc Gear
                    if (t < 0) { say("Slot: " + dump(p)); fail("khong thay Totem trong /shop"); return; }
                    clickContainer(mc, t); timeout = 0; state = S.SHOP_GEAR; wait = 8;
                } else if (++timeout > 8) state = S.SHOP_OPEN;
            }
            case SHOP_GEAR -> {
                if (newScreen(mc)) {
                    lastSync = p.currentScreenHandler.syncId;
                    int t = findContainerSlot(p, s -> s.isOf(Items.EXPERIENCE_BOTTLE));      // chai kinh nghiem
                    if (t < 0) { say("Slot: " + dump(p)); fail("khong thay chai EXP trong Gear"); return; }
                    clickContainer(mc, t); timeout = 0; state = S.SHOP_AMOUNT; wait = 8;
                } else if (++timeout > 8) { if (containerOpen(mc)) p.closeHandledScreen(); state = S.SHOP_OPEN; }
            }
            case SHOP_AMOUNT -> {
                if (newScreen(mc)) {
                    lastSync = p.currentScreenHandler.syncId;
                    int t = findContainerSlot(p, s -> { String n = nameOf(s); return n.contains("đặt thành 64") || n.contains("dat thanh 64") || n.contains("set to 64"); });
                    if (t < 0) { say("Slot: " + dump(p)); fail("khong thay nut 'dat thanh 64'"); return; }
                    clickContainer(mc, t); timeout = 0; state = S.SHOP_CONFIRM; wait = 8;
                } else if (++timeout > 8) { if (containerOpen(mc)) p.closeHandledScreen(); state = S.SHOP_OPEN; }
            }
            case SHOP_CONFIRM -> {
                // chi bam xac nhan khi so luong chai da hien 64
                boolean is64 = findContainerSlot(p, s -> s.isOf(Items.EXPERIENCE_BOTTLE) && s.getCount() == 64) >= 0;
                int t = is64 ? findContainerSlot(p, s -> { String n = nameOf(s); return n.contains("xác nhận") || n.contains("xac nhan") || n.contains("confirm"); }) : -1;
                if (t >= 0) { clickContainer(mc, t); state = S.SHOP_DONE; wait = 15; }
                else if (++timeout > 60) { say("Slot: " + dump(p)); fail("khong bam duoc xac nhan mua"); }
            }
            case SHOP_DONE -> {
                if (containerOpen(mc)) p.closeHandledScreen();                                // ESC
                if (count(p, Items.EXPERIENCE_BOTTLE) > 0) { say("Da mua chai EXP"); state = S.THROW_PREP; wait = 5; }
                else fail("mua xong nhung khong thay chai EXP (het tien?)");
            }

            // ===== NEM CHAI EXP DE SUA (KHONG DUNG TAY TRAI) =====
            // Xeng nam trong thanh hotbar. Moi vong: chon chai -> nem -> chon lai xeng (cam tay chinh)
            // -> cho vai tick de cuc EXP roi xuong chan va Mending sua xeng dang cam.
            case THROW_PREP -> {
                int bi = firstIndex(p, s -> s.isOf(Items.EXPERIENCE_BOTTLE));
                int si = findShovel(p);
                if (bi < 0) { startMining(); return; }
                if (si < 0 || si > 8) { fail("xeng phai nam tren thanh hotbar (o 1-9)"); return; }
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
                if (now <= fracBeforeThrow + 0.01f) { if (++repairNoProgress >= 2) { fail("xeng khong len do ben (co Mending khong?)"); return; } }
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
