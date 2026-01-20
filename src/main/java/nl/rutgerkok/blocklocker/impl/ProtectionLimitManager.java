package nl.rutgerkok.blocklocker.impl;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nl.rutgerkok.blocklocker.profile.PlayerProfile;
import nl.rutgerkok.blocklocker.protection.Protection;
import org.bukkit.Bukkit;
// Used? Yes
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;

/**
 * Manages protection limits for players and teams. This class handles checking whether a player or
 * team can create new protections based on configured limits.
 */
public final class ProtectionLimitManager {

  private final BlockLockerPluginImpl plugin;
  private final Config config;
  private boolean enabled;
  private boolean teamLimitsEnabled;
  private int defaultTeamLimit;
  private Map<String, Integer> teamLimits;

  public ProtectionLimitManager(BlockLockerPluginImpl plugin, Config config) {
    this.plugin = plugin;
    this.config = config;
    loadConfig();
  }

  private void loadConfig() {
    this.enabled = config.isProtectionLimitsEnabled();
    this.teamLimitsEnabled = config.isTeamLimitsEnabled();
    this.defaultTeamLimit = config.getDefaultTeamLimit();
    this.teamLimits = config.getTeamLimits();
  }

  /** Reloads the configuration. Call this after the config has been reloaded. */
  public void reload() {
    loadConfig();
  }

  /**
   * Checks if the player can create a new protection based on their current protection count and
   * limit.
   *
   * @param player The player to check.
   * @return True if the player can create a new protection, false otherwise.
   */
  public boolean canCreateProtection(Player player) {
    if (!enabled) {
      return true;
    }

    // Check bypass permission
    if (player.hasPermission("blocklocker.limit.bypass")) {
      plugin.getLogger().info("Player " + player.getName() + " bypassed limits (permission).");
      return true;
    }

    // Check team limits first if enabled
    if (teamLimitsEnabled) {
      Team team = Bukkit.getScoreboardManager().getMainScoreboard().getPlayerTeam(player);
      if (team != null) {
        int teamLimit = getTeamLimit(team);
        if (teamLimit >= 0) {
          int teamCount = getTeamProtectionCount(team);
          plugin
              .getLogger()
              .info("Team " + team.getName() + " count: " + teamCount + "/" + teamLimit);
          if (teamCount >= teamLimit) {
            return false;
          }
        }
      }
    }

    return true;
  }

  /**
   * Gets the protection limit for the specified team.
   *
   * @param team The team to check.
   * @return The limit for the team, or -1 for unlimited.
   */
  public int getTeamLimit(Team team) {
    // Check per-team override in config
    Integer override = teamLimits.get(team.getName());
    if (override != null) {
      return override;
    }

    // Fall back to default
    return defaultTeamLimit;
  }

  /**
   * Gets a formatted message describing the limit status for a player.
   *
   * @param player The player.
   * @param current The current protection count.
   * @param limit The limit.
   * @return A formatted message.
   */
  public String getLimitMessage(Player player, int current, int limit) {
    if (limit < 0) {
      return "Unlimited";
    }
    return current + "/" + limit;
  }

  /**
   * Gets whether a player is blocked by team limits.
   *
   * @param player The player to check.
   * @return True if blocked by team limits, false otherwise.
   */
  public boolean isBlockedByTeamLimit(Player player) {
    if (!enabled || !teamLimitsEnabled) {
      return false;
    }

    if (player.hasPermission("blocklocker.limit.bypass")) {
      return false;
    }

    Team team = Bukkit.getScoreboardManager().getMainScoreboard().getPlayerTeam(player);
    if (team == null) {
      return false;
    }

    int teamLimit = getTeamLimit(team);
    if (teamLimit < 0) {
      return false; // Unlimited
    }

    int teamCount = getTeamProtectionCount(team);
    return teamCount >= teamLimit;
  }

  /**
   * Helper method to determine if a block is the "main" block of a protection, to avoid counting
   * the same protection multiple times.
   *
   * @param protection The protection.
   * @param block The block to check.
   * @return True if this is likely the main block of the protection.
   */
  private boolean isMainProtectionBlock(Protection protection, Block block) {
    // Use the "getSomeProtectedBlock" as a canonical representative
    Block mainBlock = protection.getSomeProtectedBlock();
    return block.equals(mainBlock);
  }

  /**
   * Gets the current number of protections owned by the team from persistent storage.
   *
   * @param team The team to count protections for.
   * @return The number of protections owned by the team.
   */
  public int getTeamProtectionCount(Team team) {
    if (team == null) return 0;

    // Use the main world's persistent data container
    // This assumes specific world management, but usually world[0] is
    // overworld/main
    World world = Bukkit.getWorlds().get(0);
    var pdc = world.getPersistentDataContainer();
    org.bukkit.NamespacedKey key =
        new org.bukkit.NamespacedKey(plugin, "count_team_" + team.getName());

    return pdc.getOrDefault(key, org.bukkit.persistence.PersistentDataType.INTEGER, 0);
  }

  /**
   * Modifies the protection count for a team.
   *
   * @param teamName The name of the team.
   * @param delta The amount to change by (+1 for creation, -1 for destruction).
   */
  public void changeTeamCount(String teamName, int delta) {
    if (teamName == null || teamName.isEmpty()) return;

    World world = Bukkit.getWorlds().get(0);
    var pdc = world.getPersistentDataContainer();
    org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, "count_team_" + teamName);

    int current = pdc.getOrDefault(key, org.bukkit.persistence.PersistentDataType.INTEGER, 0);
    int newCount = Math.max(0, current + delta);

    if (newCount == 0) {
      pdc.remove(key);
    } else {
      pdc.set(key, org.bukkit.persistence.PersistentDataType.INTEGER, newCount);
    }
  }

  /**
   * Tries to determine the team that owns a protection.
   *
   * @param protection The protection to check.
   * @return The name of the owning team, or null if not found.
   */
  public String getTeamFromProtection(Protection protection) {
    // 1. Try to find via owner profile (Online Player)
    java.util.Optional<nl.rutgerkok.blocklocker.profile.Profile> ownerOpt = protection.getOwner();
    if (ownerOpt.isPresent()) {
      nl.rutgerkok.blocklocker.profile.Profile owner = ownerOpt.get();
      if (owner instanceof PlayerProfile playerProfile) {
        Player p = playerProfile.getUniqueId().map(Bukkit::getPlayer).orElse(null);
        if (p != null) {
          Team team = Bukkit.getScoreboardManager().getMainScoreboard().getPlayerTeam(p);
          if (team != null) return team.getName();
        } else {
          // Offline Lookup
          Optional<UUID> uuidOpt = playerProfile.getUniqueId();
          if (uuidOpt.isPresent()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuidOpt.get());
            Team team = Bukkit.getScoreboardManager().getMainScoreboard().getPlayerTeam(op);
            if (team != null) return team.getName();
          }
        }
      }
    }

    // 2. Check signs for [TeamName] tags
    for (nl.rutgerkok.blocklocker.ProtectionSign sign : protection.getSigns()) {
      for (nl.rutgerkok.blocklocker.profile.Profile p : sign.getProfiles()) {
        if (p instanceof nl.rutgerkok.blocklocker.profile.GroupProfile) {
          String dn = p.getDisplayName();
          if (dn.startsWith("[") && dn.endsWith("]")) {
            String tagName = dn.substring(1, dn.length() - 1);
            Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(tagName);
            if (t != null) {
              return t.getName();
            }
          }
        }
      }
    }
    return null;
  }
}
