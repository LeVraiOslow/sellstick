package fr.ivalona.sellwands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SellWands : batons de vente liés aux prix du /shop.
 * Clic droit sur un conteneur -> vend tout ce qui a un sell-price dans le shop,
 * multiplié par la rareté du baton (commun x1, rare x1.2, epic x1.5, mythique x2).
 */
public final class SellWandsPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String[] SHOP_CATEGORIES = {
            "blocs", "cultures", "loots", "minerais", "nourritures", "redstone", "spawners"
    };
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private record Wand(String id, double multiplier, Material material, String nexoId,
                        String name, List<String> lore, boolean glow) {}

    private NamespacedKey wandKey;
    private Economy economy;

    private final Map<String, Double> prices = new HashMap<>();
    private boolean hasNexoPrices;
    private final Map<String, Wand> wands = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<String> containers = new HashSet<>();

    private boolean plainOnly;
    private long cooldownMs;
    private String moneyFormat;
    private String sellSound;

    // ───────────── Cycle de vie ─────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        wandKey = new NamespacedKey(this, "wand");
        saveShopCopy();
        loadAll();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("sellwand");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    /** Copie la sauvegarde des prix du /shop dans plugins/SellWands/categories (une seule fois). */
    private void saveShopCopy() {
        File dir = new File(getDataFolder(), "categories");
        if (dir.exists()) return;
        for (String cat : SHOP_CATEGORIES) {
            saveResource("categories/" + cat + "/items.yml", false);
        }
    }

    private void loadAll() {
        reloadConfig();
        loadSettings();
        loadWands();
        loadPrices();
    }

    private void loadSettings() {
        ConfigurationSection s = getConfig().getConfigurationSection("settings");
        containers.clear();
        List<String> list = s != null ? s.getStringList("containers") : List.of();
        for (String c : list) containers.add(c.toUpperCase(Locale.ROOT));
        plainOnly = s == null || s.getBoolean("sell-only-plain-items", true);
        cooldownMs = s != null ? s.getLong("cooldown-ms", 500L) : 500L;
        moneyFormat = s != null ? s.getString("money-format", "<amount>$") : "<amount>$";
        sellSound = s != null ? s.getString("sound-sell", "entity.experience_orb.pickup") : "";
    }

    private void loadWands() {
        wands.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("wands");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection w = sec.getConfigurationSection(id);
            if (w == null) continue;
            Material mat = Material.matchMaterial(w.getString("material", "BLAZE_ROD"));
            if (mat == null) {
                getLogger().warning("Baton '" + id + "' : material invalide, BLAZE_ROD utilisé.");
                mat = Material.BLAZE_ROD;
            }
            wands.put(id.toLowerCase(Locale.ROOT), new Wand(
                    id.toLowerCase(Locale.ROOT),
                    w.getDouble("multiplier", 1.0),
                    mat,
                    w.getString("nexo-id", ""),
                    w.getString("name", id),
                    w.getStringList("lore"),
                    w.getBoolean("glow", false)));
        }
    }

    /** Charge tous les sell-price du /shop (categories/*/*.yml, section shop-items). */
    private void loadPrices() {
        prices.clear();
        hasNexoPrices = false;

        String path = getConfig().getString("shop.categories-folder", "");
        File root = (path == null || path.isBlank())
                ? new File(getDataFolder(), "categories")
                : new File(path);
        if (!root.isDirectory()) {
            getLogger().severe("Dossier du shop introuvable : " + root.getPath());
            return;
        }

        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File dir : dirs) {
            File[] files = dir.listFiles((d, n) ->
                    n.toLowerCase(Locale.ROOT).endsWith(".yml") && !n.equalsIgnoreCase("category.yml"));
            if (files == null) continue;
            for (File f : files) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                ConfigurationSection items = y.getConfigurationSection("shop-items");
                if (items == null) continue;
                for (String key : items.getKeys(false)) {
                    ConfigurationSection it = items.getConfigurationSection(key);
                    if (it == null) continue;
                    if (!it.getBoolean("sellable", true)) continue;
                    if (!it.contains("sell-price")) continue;
                    double p = it.getDouble("sell-price", -1);
                    if (p < 0) continue;
                    String mat = it.getString("material");
                    if (mat == null || mat.isBlank()) continue;
                    String k = mat.trim().toLowerCase(Locale.ROOT);
                    if (k.startsWith("nexo:")) hasNexoPrices = true;
                    prices.putIfAbsent(k, p);
                }
            }
        }
        getLogger().info(prices.size() + " prix de vente chargés depuis " + root.getPath());
    }

    // ───────────── Vente ─────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.useInteractedBlock() == Event.Result.DENY) return; // protection (claims...)

        ItemStack hand = e.getItem();
        Wand wand = wandOf(hand);
        if (wand == null) return;

        Player p = e.getPlayer();
        if (p.isSneaking()) return;

        Block b = e.getClickedBlock();
        if (b == null || !isAllowedContainer(b)) return;

        // C'est un baton sur un conteneur : on ne l'ouvre pas.
        e.setCancelled(true);

        if (!p.hasPermission("sellwand.use")) {
            send(p, "no-permission");
            return;
        }

        long now = System.currentTimeMillis();
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < cooldownMs) return;
        cooldowns.put(p.getUniqueId(), now);

        BlockState state = b.getState();
        if (!(state instanceof Container c)) return;
        sell(p, wand, c.getInventory());
    }

    private boolean isAllowedContainer(Block b) {
        String name = b.getType().name();
        if (containers.contains(name)) return true;
        return containers.contains("SHULKER_BOX") && name.endsWith("SHULKER_BOX");
    }

    private void sell(Player p, Wand wand, Inventory inv) {
        Economy eco = getEconomy();
        if (eco == null) {
            send(p, "no-economy");
            return;
        }

        ItemStack[] contents = inv.getContents();
        List<Integer> slots = new ArrayList<>();
        double base = 0;
        int count = 0;

        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType().isAir()) continue;
            String key = keyOf(s);
            if (plainOnly && !key.startsWith("nexo:") && s.hasItemMeta()) continue;
            Double price = prices.get(key);
            if (price == null) continue;
            base += price * s.getAmount();
            count += s.getAmount();
            slots.add(i);
        }

        if (slots.isEmpty()) {
            send(p, "nothing-to-sell");
            return;
        }

        double total = Math.round(base * wand.multiplier() * 100.0) / 100.0;

        // On encaisse d'abord : si le dépôt échoue, aucun item n'est retiré.
        EconomyResponse r = eco.depositPlayer(p, total);
        if (!r.transactionSuccess()) {
            send(p, "no-economy");
            return;
        }
        for (int slot : slots) inv.setItem(slot, null);

        send(p, "sold",
                "<count>", String.valueOf(count),
                "<amount>", money(total),
                "<multiplier>", fmt(wand.multiplier()));
        if (sellSound != null && !sellSound.isBlank()) {
            p.playSound(p.getLocation(), sellSound, 1f, 1f);
        }
    }

    private Economy getEconomy() {
        if (economy != null) return economy;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
        return economy;
    }

    // ───────────── Items ─────────────

    private Wand wandOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(wandKey, PersistentDataType.STRING);
        return id == null ? null : wands.get(id);
    }

    private ItemStack createWand(Wand w, int amount) {
        ItemStack item = null;
        if (w.nexoId() != null && !w.nexoId().isBlank()) item = nexoItem(w.nexoId());
        if (item == null) item = new ItemStack(w.material());
        item.setAmount(Math.max(1, amount));

        ItemMeta meta = item.getItemMeta();
        meta.displayName(component(w.name()));
        List<Component> lore = new ArrayList<>();
        for (String line : w.lore()) {
            lore.add(component(line.replace("<multiplier>", fmt(w.multiplier()))));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        if (w.glow()) meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, w.id());
        item.setItemMeta(meta);
        return item;
    }

    // ───────────── Nexo (optionnel, par réflexion) ─────────────

    private ItemStack nexoItem(String id) {
        try {
            Class<?> c = Class.forName("com.nexomc.nexo.api.NexoItems");
            Object builder = c.getMethod("itemFromId", String.class).invoke(null, id);
            if (builder == null) return null;
            Method build = builder.getClass().getMethod("build");
            return (ItemStack) build.invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }

    private String nexoId(ItemStack s) {
        try {
            Class<?> c = Class.forName("com.nexomc.nexo.api.NexoItems");
            Object r = c.getMethod("idFromItem", ItemStack.class).invoke(null, s);
            return r == null ? null : r.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private String keyOf(ItemStack s) {
        if (hasNexoPrices) {
            String id = nexoId(s);
            if (id != null) return "nexo:" + id.toLowerCase(Locale.ROOT);
        }
        return s.getType().name().toLowerCase(Locale.ROOT);
    }

    // ───────────── Commande ─────────────

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            send(sender, "usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (!sender.hasPermission("sellwand.admin")) { send(sender, "no-permission"); return true; }
                if (args.length < 3) { send(sender, "usage-give"); return true; }
                Player target = getServer().getPlayerExact(args[1]);
                if (target == null) { send(sender, "unknown-player"); return true; }
                Wand w = wands.get(args[2].toLowerCase(Locale.ROOT));
                if (w == null) { send(sender, "unknown-wand"); return true; }
                int amount = 1;
                if (args.length >= 4) {
                    try {
                        amount = Integer.parseInt(args[3]);
                    } catch (NumberFormatException ex) {
                        amount = -1;
                    }
                    if (amount < 1 || amount > 64) { send(sender, "invalid-amount"); return true; }
                }
                Map<Integer, ItemStack> left = target.getInventory().addItem(createWand(w, amount));
                for (ItemStack rest : left.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), rest);
                }
                String wandName = LEGACY.serialize(component(w.name()));
                send(sender, "given", "<wand>", wandName, "<amount>", String.valueOf(amount), "<player>", target.getName());
                send(target, "received", "<wand>", wandName, "<amount>", String.valueOf(amount));
            }
            case "list" -> {
                if (!sender.hasPermission("sellwand.admin")) { send(sender, "no-permission"); return true; }
                send(sender, "list", "<wands>", String.join(", ", wands.keySet()));
            }
            case "reload" -> {
                if (!sender.hasPermission("sellwand.admin")) { send(sender, "no-permission"); return true; }
                loadAll();
                send(sender, "reloaded", "<prices>", String.valueOf(prices.size()));
            }
            default -> send(sender, "unknown-subcommand");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("sellwand.admin")) return out;
        String cur = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> pool = new ArrayList<>();
        if (args.length == 1) {
            pool.addAll(List.of("give", "list", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player pl : getServer().getOnlinePlayers()) pool.add(pl.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            pool.addAll(wands.keySet());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            pool.addAll(List.of("1", "8", "64"));
        }
        for (String s : pool) if (s.toLowerCase(Locale.ROOT).startsWith(cur)) out.add(s);
        return out;
    }

    // ───────────── Utilitaires texte ─────────────

    private Component component(String text) {
        return LEGACY.deserialize(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private void send(CommandSender to, String key, String... replacements) {
        String msg = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        String prefix = getConfig().getString("messages.prefix", "");
        to.sendMessage(LEGACY.deserialize(prefix + msg));
    }

    private static String fmt(double v) {
        return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US)).format(v);
    }

    private String money(double v) {
        String n = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.FRANCE)).format(v);
        return moneyFormat.replace("<amount>", n);
    }
}
