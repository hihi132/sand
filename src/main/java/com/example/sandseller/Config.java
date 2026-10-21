package com.example.sandseller;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Cai dat luu o config/sandseller.json (chinh trong menu, phim O). */
public class Config {
    public int sandLimit = 1200;
    public String sellCommand = "order";
    public String shopCommand = "shop";
    public boolean autoEat = true;
    public boolean autoRepair = true;
    public int repairStart = 20;      // % do ben: bat dau sua khi <=
    public int repairStop = 80;       // % do ben: dung sua khi >=
    public boolean attackMobs = true; // tu danh quai
    public double attackRange = 3.0;  // tam danh (block)
    public boolean debug = true;      // in thong tin chi tiet trong chat
    public int settleTicks = 10;      // cho man hinh dung yen bao nhieu tick truoc khi bam (server lag thi tang)

    public static Config I = new Config();

    static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("sandseller.json"); }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Config c = new Gson().fromJson(Files.readString(file()), Config.class);
                if (c != null) I = c;
            }
        } catch (Exception ignored) { I = new Config(); }
        if (I.sellCommand == null || I.sellCommand.isBlank()) I.sellCommand = "order";
        if (I.shopCommand == null || I.shopCommand.isBlank()) I.shopCommand = "shop";
        if (I.attackRange < 1.5 || I.attackRange > 6) I.attackRange = 3.0;
        if (I.settleTicks < 2 || I.settleTicks > 100) I.settleTicks = 10;
    }

    public static void save() {
        try { Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(I)); }
        catch (Exception ignored) { }
    }
}
