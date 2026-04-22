package com.alchemycodes;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class AlchemyCodesPlugin extends JavaPlugin implements TabCompleter {

    private static final String RANDOM_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final List<String> ALLOWED_KEY_REWARDS = List.of("common_key", "epic_key", "spawner_key", "legendary_key");
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private final Random random = new Random();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private FileConfiguration messages;
    private FileConfiguration playerData;
    private File playerDataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("playerdata.yml");
        reloadInternal();

        Objects.requireNonNull(getCommand("alchemycodes")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("redeem")).setTabCompleter(this);
    }

    private void saveResourceIfMissing(String resource) {
        File out = new File(getDataFolder(), resource);
        if (!out.exists()) {
            saveResource(resource, false);
        }
    }

    private void reloadInternal() {
        reloadConfig();
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        playerDataFile = new File(getDataFolder(), "playerdata.yml");
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
    }

    private void savePlayerData() {
        try {
            playerData.save(playerDataFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save playerdata.yml: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("redeem")) {
            return handleRedeem(sender, args);
        }
        if (name.equals("alchemycodes")) {
            return handleAdmin(sender, args);
        }
        return false;
    }

    private boolean handleRedeem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(message("only_players")));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(color(message("redeem_usage")));
            return true;
        }

        String codeName = args[0].toUpperCase(Locale.ROOT);
        ConfigurationSection code = getConfig().getConfigurationSection("codes." + codeName);
        if (code == null) {
            player.sendMessage(color(message("invalid_code")));
            logWebhook("failed-attempts", "Blocked Redemption", 15158332,
                    field("Player", player.getName(), true),
                    field("UUID", player.getUniqueId().toString(), false),
                    field("Code", codeName, true),
                    field("Reason", "Code does not exist", false),
                    field("IP", formatIpForLog(getPlayerIp(player)), true),
                    field("Time", formatNow(), true));
            return true;
        }

        String permission = code.getString("permission", "").trim();
        if (getConfig().getBoolean("settings.require-code-permission", false) && !permission.isEmpty() && !player.hasPermission(permission)) {
            player.sendMessage(color(message("code_permission_missing")));
            logWebhook("failed-attempts", "Blocked Redemption", 15158332,
                    field("Player", player.getName(), true),
                    field("UUID", player.getUniqueId().toString(), false),
                    field("Code", codeName, true),
                    field("Reason", "Missing permission: " + permission, false),
                    field("IP", formatIpForLog(getPlayerIp(player)), true),
                    field("Time", formatNow(), true));
            return true;
        }

        String expiration = code.getString("expiration", "").trim();
        if (!expiration.isEmpty()) {
            try {
                if (Instant.now().isAfter(Instant.parse(expiration))) {
                    player.sendMessage(color(message("code_expired")));
                    logWebhook("expired-attempts", "Expired Code Attempt", 9807270,
                            field("Player", player.getName(), true),
                            field("UUID", player.getUniqueId().toString(), false),
                            field("Code", codeName, true),
                            field("Expiration", expiration, false),
                            field("IP", formatIpForLog(getPlayerIp(player)), true),
                            field("Time", formatNow(), true));
                    return true;
                }
            } catch (DateTimeParseException ignored) {
                getLogger().warning("Invalid expiration format for code " + codeName + ": " + expiration);
            }
        }

        String newPlayerFailure = validateNewPlayerRequirements(player, code, codeName);
        if (newPlayerFailure != null) {
            player.sendMessage(color(message("new_player_only_blocked").replace("%reason%", newPlayerFailure)));
            logWebhook("failed-attempts", "Blocked Redemption", 15158332,
                    field("Player", player.getName(), true),
                    field("UUID", player.getUniqueId().toString(), false),
                    field("Code", codeName, true),
                    field("Reason", newPlayerFailure, false),
                    field("IP", formatIpForLog(getPlayerIp(player)), true),
                    field("Time", formatNow(), true));
            return true;
        }

        String playerPath = "players." + player.getUniqueId() + ".redeemed." + codeName;
        if (playerData.getBoolean(playerPath, false)) {
            player.sendMessage(color(message("player_already_redeemed")));
            logWebhook("failed-attempts", "Blocked Redemption", 15158332,
                    field("Player", player.getName(), true),
                    field("UUID", player.getUniqueId().toString(), false),
                    field("Code", codeName, true),
                    field("Reason", "Player already redeemed this code", false),
                    field("IP", formatIpForLog(getPlayerIp(player)), true),
                    field("Time", formatNow(), true));
            return true;
        }

        String ip = getPlayerIp(player);
        if (!ip.isEmpty() && playerData.getBoolean("ips." + ip + ".redeemed." + codeName, false)) {
            player.sendMessage(color(message("ip_already_redeemed")));
            logWebhook("failed-attempts", "Blocked Redemption", 15158332,
                    field("Player", player.getName(), true),
                    field("UUID", player.getUniqueId().toString(), false),
                    field("Code", codeName, true),
                    field("Reason", "IP already redeemed this code", false),
                    field("IP", formatIpForLog(ip), true),
                    field("Time", formatNow(), true));
            return true;
        }

        int maxUses = code.getInt("uses", -1);
        int currentUses = code.getInt("redeemed-count", 0);
        if (maxUses >= 0 && currentUses >= maxUses) {
            player.sendMessage(color(message("max_uses_reached")));
            logWebhook("failed-attempts", "Blocked Redemption", 15158332,
                    field("Player", player.getName(), true),
                    field("UUID", player.getUniqueId().toString(), false),
                    field("Code", codeName, true),
                    field("Reason", "Max uses reached", false),
                    field("IP", formatIpForLog(ip), true),
                    field("Time", formatNow(), true));
            return true;
        }

        List<String> commands = code.getStringList("commands");
        if (commands.isEmpty()) {
            player.sendMessage(color(message("code_has_no_rewards")));
            logWebhook("system-errors", "Code Reward Error", 15105570,
                    field("Player", player.getName(), true),
                    field("UUID", player.getUniqueId().toString(), false),
                    field("Code", codeName, true),
                    field("Reason", "Code has no reward commands configured", false),
                    field("Time", formatNow(), true));
            return true;
        }

        List<String> executedCommands = new ArrayList<>();
        boolean commandFailure = false;
        for (String cmd : commands) {
            String parsed = cmd.replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString())
                    .replace("%code%", codeName);
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
            executedCommands.add((success ? "✔ " : "✖ ") + parsed);
            if (!success) {
                commandFailure = true;
                logWebhook("system-errors", "Reward Command Failed", 15105570,
                        field("Player", player.getName(), true),
                        field("UUID", player.getUniqueId().toString(), false),
                        field("Code", codeName, true),
                        field("Command", truncate(parsed, 1000), false),
                        field("IP", formatIpForLog(ip), true),
                        field("Time", formatNow(), true));
            }
        }

        code.set("redeemed-count", currentUses + 1);
        playerData.set(playerPath, true);
        playerData.set("players." + player.getUniqueId() + ".name", player.getName());
        if (!ip.isEmpty()) {
            playerData.set("players." + player.getUniqueId() + ".ip", ip);
            playerData.set("ips." + ip + ".redeemed." + codeName, true);
            playerData.set("ips." + ip + ".last-player", player.getName());
        }
        saveConfig();
        savePlayerData();

        player.sendMessage(color(message("redeem_success").replace("%code%", codeName)));
        logWebhook("redeem-success", "Code Redeemed", 5763719,
                field("Player", player.getName(), true),
                field("UUID", player.getUniqueId().toString(), false),
                field("Code", codeName, true),
                field("IP", formatIpForLog(ip), true),
                field("Reward Count", String.valueOf(commands.size()), true),
                field("Command Result", commandFailure ? "Completed with one or more failed reward commands" : "All reward commands executed", false),
                field("Rewards", truncate(String.join("\n", executedCommands), 1000), false),
                field("Time", formatNow(), true));
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        String adminPermission = getConfig().getString("settings.admin-permission", "alchemycodes.admin");
        if (!sender.hasPermission(adminPermission)) {
            sender.sendMessage(color(message("no_permission")));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(color(message("admin_usage")));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadInternal();
                sender.sendMessage(color(message("reload_success")));
            }
            case "help" -> sendHelp(sender);
            case "listcodes" -> listCodes(sender);
            case "createcode" -> createCode(sender, args);
            case "generatecode" -> generateCode(sender, args);
            case "deletecode" -> deleteCode(sender, args);
            case "setuses" -> setUses(sender, args);
            case "setduration" -> setDuration(sender, args);
            case "setpermission" -> setPermission(sender, args);
            case "setnewplayer" -> setNewPlayer(sender, args);
            case "addreward" -> addReward(sender, args);
            case "removereward" -> removeReward(sender, args);
            case "listrewards" -> listRewards(sender, args);
            case "checkcodes" -> checkCodes(sender, args);
            case "reset" -> resetCode(sender, args);
            default -> sender.sendMessage(color(message("admin_usage")));
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color(message("help_header")));
        for (int i = 1; i <= 13; i++) {
            String line = messages.getString("help_line_" + i);
            if (line != null && !line.isEmpty()) {
                sender.sendMessage(color(line));
            }
        }
    }

    private void listCodes(CommandSender sender) {
        ConfigurationSection codes = getConfig().getConfigurationSection("codes");
        if (codes == null || codes.getKeys(false).isEmpty()) {
            sender.sendMessage(color(message("no_codes")));
            return;
        }

        sender.sendMessage(color(message("codes_header")));
        for (String key : new TreeSet<>(codes.getKeys(false))) {
            int uses = getConfig().getInt("codes." + key + ".uses", -1);
            int redeemed = getConfig().getInt("codes." + key + ".redeemed-count", 0);
            String expires = formatExpiration(getConfig().getString("codes." + key + ".expiration", ""));
            int rewards = getConfig().getStringList("codes." + key + ".commands").size();
            boolean newPlayerOnly = getConfig().getBoolean("codes." + key + ".new-player.enabled", false);
            sender.sendMessage(color("&7- &b" + key + " &7(uses: &f" + uses + "&7, redeemed: &f" + redeemed + "&7, rewards: &f" + rewards + "&7, expires: &f" + expires + "&7, new-player: &f" + (newPlayerOnly ? "yes" : "no") + "&7)"));
        }
    }

    private void createCode(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(message("createcode_usage")));
            return;
        }

        String codeName = args[1].toUpperCase(Locale.ROOT);
        if (getConfig().isConfigurationSection("codes." + codeName)) {
            sender.sendMessage(color(message("code_exists")));
            return;
        }

        int uses = parseInteger(args[2]);
        if (uses == Integer.MIN_VALUE) {
            sender.sendMessage(color(message("invalid_number")));
            return;
        }

        String durationInput = args.length >= 4 ? args[3] : "";
        String expiration = resolveExpirationInput(durationInput);
        if (durationInput != null && !durationInput.isEmpty() && expiration == null) {
            sender.sendMessage(color(message("invalid_duration")));
            return;
        }

        createCodeSection(codeName, uses, expiration);
        sender.sendMessage(color(message("code_created").replace("%code%", codeName)));
        logWebhook("admin-actions", "Code Created", 16705372,
                field("Admin", sender.getName(), true),
                field("Code", codeName, true),
                field("Uses", String.valueOf(uses), true),
                field("Expiration", expiration == null || expiration.isBlank() ? "none" : expiration, false),
                field("Time", formatNow(), true));
    }

    private void generateCode(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(message("generatecode_usage")));
            return;
        }

        int length = parseInteger(args[1]);
        int uses = parseInteger(args[2]);
        if (length == Integer.MIN_VALUE || uses == Integer.MIN_VALUE || length < 4 || length > 32) {
            sender.sendMessage(color(message("invalid_number")));
            return;
        }

        String durationInput = args.length >= 4 ? args[3] : "";
        String expiration = resolveExpirationInput(durationInput);
        if (durationInput != null && !durationInput.isEmpty() && expiration == null) {
            sender.sendMessage(color(message("invalid_duration")));
            return;
        }

        String codeName = generateUniqueCode(length);
        createCodeSection(codeName, uses, expiration);
        sender.sendMessage(color(message("code_generated").replace("%code%", codeName)));
        logWebhook("admin-actions", "Random Code Generated", 16705372,
                field("Admin", sender.getName(), true),
                field("Code", codeName, true),
                field("Length", String.valueOf(length), true),
                field("Uses", String.valueOf(uses), true),
                field("Expiration", expiration == null || expiration.isBlank() ? "none" : expiration, false),
                field("Time", formatNow(), true));
    }

    private void createCodeSection(String codeName, int uses, String expiration) {
        getConfig().set("codes." + codeName + ".uses", uses);
        getConfig().set("codes." + codeName + ".permission", "");
        getConfig().set("codes." + codeName + ".expiration", expiration == null ? "" : expiration);
        getConfig().set("codes." + codeName + ".new-player.enabled", false);
        getConfig().set("codes." + codeName + ".new-player.max-account-age", "");
        getConfig().set("codes." + codeName + ".new-player.max-playtime-minutes", -1);
        getConfig().set("codes." + codeName + ".new-player.require-both", true);
        getConfig().set("codes." + codeName + ".commands", new ArrayList<String>());
        getConfig().set("codes." + codeName + ".redeemed-count", 0);
        saveConfig();
    }

    private void deleteCode(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color(message("deletecode_usage")));
            return;
        }

        String codeName = args[1].toUpperCase(Locale.ROOT);
        if (!getConfig().isConfigurationSection("codes." + codeName)) {
            sender.sendMessage(color(message("invalid_code")));
            return;
        }

        getConfig().set("codes." + codeName, null);
        saveConfig();
        sender.sendMessage(color(message("code_deleted").replace("%code%", codeName)));
        logWebhook("admin-actions", "Code Deleted", 16705372,
                field("Admin", sender.getName(), true),
                field("Code", codeName, true),
                field("Time", formatNow(), true));
    }

    private void setUses(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(message("setuses_usage")));
            return;
        }

        String codeName = args[1].toUpperCase(Locale.ROOT);
        if (!getConfig().isConfigurationSection("codes." + codeName)) {
            sender.sendMessage(color(message("invalid_code")));
            return;
        }

        int uses = parseInteger(args[2]);
        if (uses == Integer.MIN_VALUE) {
            sender.sendMessage(color(message("invalid_number")));
            return;
        }

        getConfig().set("codes." + codeName + ".uses", uses);
        saveConfig();
        sender.sendMessage(color(message("uses_changed").replace("%code%", codeName).replace("%uses%", String.valueOf(uses))));
        logWebhook("admin-actions", "Code Uses Updated", 16705372,
                field("Admin", sender.getName(), true),
                field("Code", codeName, true),
                field("Uses", String.valueOf(uses), true),
                field("Time", formatNow(), true));
    }

    private void setDuration(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(message("setduration_usage")));
            return;
        }

        String codeName = args[1].toUpperCase(Locale.ROOT);
        if (!getConfig().isConfigurationSection("codes." + codeName)) {
            sender.sendMessage(color(message("invalid_code")));
            return;
        }

        String input = args[2];
        if (input.equalsIgnoreCase("none") || input.equalsIgnoreCase("remove")) {
            getConfig().set("codes." + codeName + ".expiration", "");
            saveConfig();
            sender.sendMessage(color(message("duration_cleared").replace("%code%", codeName)));
            logWebhook("admin-actions", "Code Duration Cleared", 16705372,
                    field("Admin", sender.getName(), true),
                    field("Code", codeName, true),
                    field("Time", formatNow(), true));
            return;
        }

        String expiration = resolveExpirationInput(input);
        if (expiration == null) {
            sender.sendMessage(color(message("invalid_duration")));
            return;
        }

        getConfig().set("codes." + codeName + ".expiration", expiration);
        saveConfig();
        sender.sendMessage(color(message("duration_set").replace("%code%", codeName).replace("%duration%", input).replace("%expires%", expiration)));
        logWebhook("admin-actions", "Code Duration Updated", 16705372,
                field("Admin", sender.getName(), true),
                field("Code", codeName, true),
                field("Duration Input", input, true),
                field("Expires At", expiration, false),
                field("Time", formatNow(), true));
    }

    private void setPermission(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(message("setpermission_usage")));
            return;
        }

        String codeName = args[1].toUpperCase(Locale.ROOT);
        if (!getConfig().isConfigurationSection("codes." + codeName)) {
            sender.sendMessage(color(message("invalid_code")));
            return;
        }

        String permission = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
        if (permission.equalsIgnoreCase("none") || permission.equalsIgnoreCase("remove")) {
            permission = "";
        }

        getConfig().set("codes." + codeName + ".permission", permission);
        saveConfig();
        sender.sendMessage(color(message("permission_set").replace("%code%", codeName).replace("%permission%", permission.isEmpty() ? "none" : permission)));
        logWebhook("admin-actions", "Code Permission Updated", 16705372,
                field("Admin", sender.getName(), true),
                field("Code", codeName, true),
                field("Permission", permission.isEmpty() ? "none" : permission, false),
                field("Time", formatNow(), true));
    }

    private void setNewPlayer(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(message("setnewplayer_usage")));
            return;
        }

        String codeName = args[1].toUpperCase(Locale.ROOT);
        if (!getConfig().isConfigurationSection("codes." + codeName)) {
            sender.sendMessage(color(message("invalid_code")));
            return;
        }

        String accountAgeInput = args[2];
        if (accountAgeInput.equalsIgnoreCase("none") || accountAgeInput.equalsIgnoreCase("false") || accountAgeInput.equalsIgnoreCase("disable")) {
            getConfig().set("codes." + codeName + ".new-player.enabled", false);
            getConfig().set("codes." + codeName + ".new-player.max-account-age", "");
            getConfig().set("codes." + codeName + ".new-player.max-playtime-minutes", -1);
            saveConfig();
            sender.sendMessage(color(message("newplayer_disabled").replace("%code%", codeName)));
            logWebhook("admin-actions", "New Player Restriction Disabled", 16705372,
                    field("Admin", sender.getName(), true),
                    field("Code", codeName, true),
                    field("Time", formatNow(), true));
            return;
        }

        Duration accountAge = parseDuration(accountAgeInput);
        if (accountAge == null || accountAge.isZero() || accountAge.isNegative()) {
            sender.sendMessage(color(message("invalid_duration")));
            return;
        }

        int maxPlaytimeMinutes = -1;
        if (args.length >= 4) {
            maxPlaytimeMinutes = parseInteger(args[3]);
            if (maxPlaytimeMinutes == Integer.MIN_VALUE) {
                sender.sendMessage(color(message("invalid_number")));
                return;
            }
        }

        getConfig().set("codes." + codeName + ".new-player.enabled", true);
        getConfig().set("codes." + codeName + ".new-player.max-account-age", accountAgeInput.toLowerCase(Locale.ROOT));
        getConfig().set("codes." + codeName + ".new-player.max-playtime-minutes", maxPlaytimeMinutes);
        getConfig().set("codes." + codeName + ".new-player.require-both", true);
        saveConfig();
        sender.sendMessage(color(message("newplayer_set")
                .replace("%code%", codeName)
                .replace("%age%", accountAgeInput)
                .replace("%minutes%", maxPlaytimeMinutes < 0 ? "unlimited" : String.valueOf(maxPlaytimeMinutes))));
        logWebhook("admin-actions", "New Player Restriction Updated", 16705372,
                field("Admin", sender.getName(), true),
                field("Code", codeName, true),
                field("Max Account Age", accountAgeInput, true),
                field("Max Playtime Minutes", maxPlaytimeMinutes < 0 ? "unlimited" : String.valueOf(maxPlaytimeMinutes), true),
                field("Time", formatNow(), true));
    }

    private void addReward(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(message("addreward_usage")));
            return;
        }

        String codeName = args[1].toUpperCase(Locale.ROOT);
        if (!getConfig().isConfigurationSection("codes." + codeName)) {
            sender.sendMessage(color(message("invalid_code")));
            return;
        }

        String reward = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
        if (reward.isEmpty()) {
            sender.sendMessage(color(message("reward_empty")));
            return;
        }
        String rewardValidationError = validateRewardCommand(reward);
        if (rewardValidationError != null) {
            sender.sendMessage(color(message("invalid_reward_command").replace("%reason%", rewardValidationError)));
            return;
        }

        List<String> commands = new ArrayList<>(getConfig().getStringList("codes." + codeName + ".commands"));
        commands.add(reward);
        getConfig().set("codes." + codeName + ".commands", commands);
        saveConfig();
        sender.sendMessage(color(message("reward_added").replace("%code%", codeName)));
        logWebhook("admin-actions", "Reward Added", 16705372,
                field("Admin", sender.getName(), true),
                field("Code", codeName, true),
                field("Reward", truncate(reward, 1000), false),
                field("Total Rewards", String.valueOf(commands.size()), true),
                field("Time", formatNow(), true));
    }

    private void removeReward(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(message("removereward_usage")));
            return;
        }

        String codeName = args[1].toUpperCase(Locale.ROOT);
        if (!getConfig().isConfigurationSection("codes." + codeName)) {
            sender.sendMessage(color(message("invalid_code")));
            return;
        }

        int index = parseInteger(args[2]);
        if (index == Integer.MIN_VALUE || index <= 0) {
            sender.sendMessage(color(message("invalid_number")));
            return;
        }

        List<String> commands = new ArrayList<>(getConfig().getStringList("codes." + codeName + ".commands"));
        if (index > commands.size()) {
            sender.sendMessage(color(message("invalid_reward_index")));
            return;
        }

        String removed = commands.remove(index - 1);
        getConfig().set("codes." + codeName + ".commands", commands);
        saveConfig();
        sender.sendMessage(color(message("reward_removed").replace("%code%", codeName).replace("%index%", String.valueOf(index))));
        logWebhook("admin-actions", "Reward Removed", 16705372,
                field("Admin", sender.getName(), true),
                field("Code", codeName, true),
                field("Index", String.valueOf(index), true),
                field("Removed Reward", truncate(removed, 1000), false),
                field("Total Rewards", String.valueOf(commands.size()), true),
                field("Time", formatNow(), true));
    }

    private void listRewards(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color(message("listrewards_usage")));
            return;
        }

        String codeName = args[1].toUpperCase(Locale.ROOT);
        if (!getConfig().isConfigurationSection("codes." + codeName)) {
            sender.sendMessage(color(message("invalid_code")));
            return;
        }

        List<String> commands = getConfig().getStringList("codes." + codeName + ".commands");
        if (commands.isEmpty()) {
            sender.sendMessage(color(message("listrewards_none").replace("%code%", codeName)));
            return;
        }

        sender.sendMessage(color(message("listrewards_header").replace("%code%", codeName)));
        for (int i = 0; i < commands.size(); i++) {
            sender.sendMessage(color("&7" + (i + 1) + ". &f" + commands.get(i)));
        }
    }

    private void checkCodes(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color(message("checkcodes_usage")));
            return;
        }

        String targetName = args[1];
        ConfigurationSection players = playerData.getConfigurationSection("players");
        if (players == null) {
            sender.sendMessage(color(message("checkcodes_none")));
            return;
        }

        for (String uuid : players.getKeys(false)) {
            String storedName = playerData.getString("players." + uuid + ".name", "");
            if (!storedName.equalsIgnoreCase(targetName)) {
                continue;
            }

            ConfigurationSection redeemed = playerData.getConfigurationSection("players." + uuid + ".redeemed");
            if (redeemed == null || redeemed.getKeys(false).isEmpty()) {
                sender.sendMessage(color(message("checkcodes_none")));
                return;
            }

            sender.sendMessage(color(message("checkcodes_header").replace("%player%", storedName)));
            for (String code : new TreeSet<>(redeemed.getKeys(false))) {
                sender.sendMessage(color("&7- &b" + code));
            }
            return;
        }

        sender.sendMessage(color(message("invalid_player")));
    }

    private void resetCode(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(message("reset_usage")));
            return;
        }

        String targetName = args[1];
        String codeName = args[2].toUpperCase(Locale.ROOT);
        ConfigurationSection players = playerData.getConfigurationSection("players");
        if (players == null) {
            sender.sendMessage(color(message("invalid_player")));
            return;
        }

        for (String uuid : players.getKeys(false)) {
            String storedName = playerData.getString("players." + uuid + ".name", "");
            if (!storedName.equalsIgnoreCase(targetName)) {
                continue;
            }

            String playerRedeemPath = "players." + uuid + ".redeemed." + codeName;
            if (!playerData.getBoolean(playerRedeemPath, false)) {
                sender.sendMessage(color(message("invalid_code")));
                return;
            }

            String ip = playerData.getString("players." + uuid + ".ip", "");
            playerData.set(playerRedeemPath, null);
            if (!ip.isEmpty()) {
                playerData.set("ips." + ip + ".redeemed." + codeName, null);
            }

            int redeemedCount = getConfig().getInt("codes." + codeName + ".redeemed-count", 0);
            if (redeemedCount > 0) {
                getConfig().set("codes." + codeName + ".redeemed-count", redeemedCount - 1);
                saveConfig();
            }
            savePlayerData();
            sender.sendMessage(color(message("code_reset")));
            logWebhook("admin-actions", "Code Redemption Reset", 16705372,
                    field("Admin", sender.getName(), true),
                    field("Player", storedName, true),
                    field("UUID", uuid, false),
                    field("Code", codeName, true),
                    field("IP", formatIpForLog(ip), true),
                    field("Time", formatNow(), true));
            return;
        }

        sender.sendMessage(color(message("invalid_player")));
    }

    private String validateNewPlayerRequirements(Player player, ConfigurationSection code, String codeName) {
        ConfigurationSection section = code.getConfigurationSection("new-player");
        if (section == null || !section.getBoolean("enabled", false)) {
            return null;
        }

        String ageInput = section.getString("max-account-age", "").trim();
        Duration maxAge = ageInput.isEmpty() ? null : parseDuration(ageInput);
        int maxPlaytimeMinutes = section.getInt("max-playtime-minutes", -1);
        boolean requireBoth = section.getBoolean("require-both", true);

        List<String> failures = new ArrayList<>();
        OfflinePlayer offlinePlayer = player;
        if (maxAge != null) {
            long firstPlayed = offlinePlayer.getFirstPlayed();
            if (firstPlayed <= 0L) {
                failures.add("This code is only for newly joined players.");
            } else {
                long accountAgeMillis = System.currentTimeMillis() - firstPlayed;
                if (accountAgeMillis > maxAge.toMillis()) {
                    failures.add("Account age is older than " + ageInput + ".");
                }
            }
        }

        if (maxPlaytimeMinutes >= 0) {
            long ticksPlayed = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            long playedMinutes = ticksPlayed / 20L / 60L;
            if (playedMinutes > maxPlaytimeMinutes) {
                failures.add("Playtime is higher than " + maxPlaytimeMinutes + " minutes.");
            }
        }

        if (failures.isEmpty()) {
            return null;
        }
        if (!requireBoth && failures.size() < 2) {
            return null;
        }
        return String.join(" ", failures);
    }

    private String validateRewardCommand(String reward) {
        String lowered = reward.trim().toLowerCase(Locale.ROOT);
        if (!lowered.contains("givekey")) {
            return null;
        }

        List<String> parts = java.util.Arrays.stream(reward.trim().split("\s+"))
                .filter(part -> !part.isBlank())
                .toList();
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).equalsIgnoreCase("givekey")) {
                if (i + 2 >= parts.size()) {
                    return "Key reward format must be: <plugin> givekey %player% <key> <amount>";
                }
                String keyName = parts.get(i + 2).toLowerCase(Locale.ROOT);
                if (!ALLOWED_KEY_REWARDS.contains(keyName)) {
                    return "Allowed keys: " + String.join(", ", ALLOWED_KEY_REWARDS);
                }
                return null;
            }
        }
        return null;
    }

    private int parseInteger(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException ex) {
            return Integer.MIN_VALUE;
        }
    }

    private String resolveExpirationInput(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        try {
            return Instant.parse(input).toString();
        } catch (DateTimeParseException ignored) {
        }

        Duration duration = parseDuration(input);
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return null;
        }
        return Instant.now().plus(duration).toString();
    }

    private Duration parseDuration(String input) {
        String cleaned = input.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (cleaned.isEmpty()) {
            return null;
        }

        long totalSeconds = 0L;
        StringBuilder number = new StringBuilder();
        for (int i = 0; i < cleaned.length(); i++) {
            char current = cleaned.charAt(i);
            if (Character.isDigit(current)) {
                number.append(current);
                continue;
            }

            if (number.length() == 0) {
                return null;
            }

            long value;
            try {
                value = Long.parseLong(number.toString());
            } catch (NumberFormatException ex) {
                return null;
            }
            number.setLength(0);

            switch (current) {
                case 'y' -> totalSeconds += value * 31_536_000L;
                case 'd' -> totalSeconds += value * 86_400L;
                case 'h' -> totalSeconds += value * 3_600L;
                case 'm' -> totalSeconds += value * 60L;
                case 's' -> totalSeconds += value;
                default -> {
                    return null;
                }
            }
        }

        if (number.length() > 0) {
            return null;
        }
        return Duration.ofSeconds(totalSeconds);
    }

    private String generateUniqueCode(int length) {
        for (int attempts = 0; attempts < 1000; attempts++) {
            String generated = randomCode(length);
            if (!getConfig().isConfigurationSection("codes." + generated)) {
                return generated;
            }
        }
        throw new IllegalStateException("Unable to generate a unique code after many attempts.");
    }

    private String randomCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(RANDOM_CHARACTERS.charAt(random.nextInt(RANDOM_CHARACTERS.length())));
        }
        return builder.toString();
    }

    private String formatExpiration(String expiration) {
        if (expiration == null || expiration.isBlank()) {
            return "never";
        }
        return expiration;
    }

    private String getPlayerIp(Player player) {
        if (player.getAddress() == null) {
            return "";
        }
        InetAddress address = player.getAddress().getAddress();
        return address != null ? address.getHostAddress() : "";
    }

    private String formatIpForLog(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        if (!getConfig().getBoolean("webhook.mask-ip", true)) {
            return ip;
        }

        if (ip.contains(".")) {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + ".*.*";
            }
        }

        if (ip.contains(":")) {
            String[] parts = ip.split(":");
            if (parts.length >= 2) {
                return parts[0] + ":" + parts[1] + ":*:*:*:*";
            }
        }

        return "masked";
    }

    private String formatNow() {
        return LOG_TIME_FORMAT.format(Instant.now());
    }

    private String message(String path) {
        String prefix = messages.getString("prefix", "&8[&bAlchemyCodes&8] ");
        String raw = messages.getString(path, path);
        return raw.replace("%prefix%", prefix);
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private void logWebhook(String logType, String title, int color, DiscordField... fields) {
        if (!getConfig().getBoolean("webhook.enabled", false)) {
            return;
        }
        if (!getConfig().getBoolean("webhook.types." + logType, false)) {
            return;
        }

        String webhookUrl = getConfig().getString("webhook.url", "").trim();
        if (webhookUrl.isEmpty()) {
            return;
        }

        String payload = buildWebhookPayload(title, color, fields);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> sendWebhookPayload(webhookUrl, payload));
    }

    private String buildWebhookPayload(String title, int color, DiscordField... fields) {
        String username = getConfig().getString("webhook.username", "AlchemyCodes");
        String avatarUrl = getConfig().getString("webhook.avatar-url", "");
        String footerText = getConfig().getString("webhook.footer-text", "AlchemyCodes Logger");
        String footerIcon = getConfig().getString("webhook.footer-icon-url", "");

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"username\":\"").append(escapeJson(username)).append("\",");
        if (!avatarUrl.isBlank()) {
            json.append("\"avatar_url\":\"").append(escapeJson(avatarUrl)).append("\",");
        }
        json.append("\"embeds\":[{");
        json.append("\"title\":\"").append(escapeJson(title)).append("\",");
        json.append("\"color\":").append(color).append(",");
        json.append("\"fields\":[");
        for (int i = 0; i < fields.length; i++) {
            DiscordField field = fields[i];
            json.append("{")
                    .append("\"name\":\"").append(escapeJson(field.name())).append("\",")
                    .append("\"value\":\"").append(escapeJson(field.value())).append("\",")
                    .append("\"inline\":").append(field.inline())
                    .append("}");
            if (i + 1 < fields.length) {
                json.append(",");
            }
        }
        json.append("],");
        json.append("\"footer\":{")
                .append("\"text\":\"").append(escapeJson(footerText)).append("\"");
        if (!footerIcon.isBlank()) {
            json.append(",\"icon_url\":\"").append(escapeJson(footerIcon)).append("\"");
        }
        json.append("}");
        json.append("}]}");
        return json.toString();
    }

    private void sendWebhookPayload(String webhookUrl, String payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                getLogger().warning("Discord webhook returned HTTP " + status);
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to send Discord webhook: " + ex.getMessage());
        }
    }

    private DiscordField field(String name, String value, boolean inline) {
        return new DiscordField(name, value == null || value.isBlank() ? "none" : value, inline);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : input.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("redeem")) {
            if (args.length == 1) {
                ConfigurationSection codes = getConfig().getConfigurationSection("codes");
                if (codes == null) {
                    return Collections.emptyList();
                }
                return partial(args[0], new ArrayList<>(codes.getKeys(false)));
            }
            return Collections.emptyList();
        }

        if (!command.getName().equalsIgnoreCase("alchemycodes")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return partial(args[0], List.of("reload", "help", "listcodes", "createcode", "generatecode", "deletecode", "setuses", "setduration", "setpermission", "setnewplayer", "addreward", "removereward", "listrewards", "reset", "checkcodes"));
        }

        if (args.length == 2 && List.of("deletecode", "setuses", "setduration", "setpermission", "setnewplayer", "addreward", "removereward", "listrewards", "reset").contains(args[0].toLowerCase(Locale.ROOT))) {
            if (args[0].equalsIgnoreCase("reset")) {
                return partial(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
            }
            ConfigurationSection codes = getConfig().getConfigurationSection("codes");
            return codes == null ? Collections.emptyList() : partial(args[1], new ArrayList<>(codes.getKeys(false)));
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("checkcodes")) {
            return partial(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            ConfigurationSection codes = getConfig().getConfigurationSection("codes");
            return codes == null ? Collections.emptyList() : partial(args[2], new ArrayList<>(codes.getKeys(false)));
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("setpermission")) {
            return partial(args[2], List.of("none"));
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("setduration")) {
            return partial(args[2], List.of("1d", "7d", "30d", "12h", "none"));
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("setnewplayer")) {
            return partial(args[2], List.of("1d", "7d", "14d", "30d", "none"));
        }

        return Collections.emptyList();
    }

    private List<String> partial(String input, List<String> options) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private record DiscordField(String name, String value, boolean inline) {
    }
}
