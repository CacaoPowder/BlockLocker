package nl.rutgerkok.blocklocker.impl.event;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import nl.rutgerkok.blocklocker.AttackType;
import nl.rutgerkok.blocklocker.Permissions;
import nl.rutgerkok.blocklocker.ProtectionSign;
import nl.rutgerkok.blocklocker.Translator.Translation;
import nl.rutgerkok.blocklocker.impl.BlockLockerPluginImpl;
import nl.rutgerkok.blocklocker.impl.ProtectionLimitManager;
import nl.rutgerkok.blocklocker.profile.Profile;
import nl.rutgerkok.blocklocker.protection.Protection;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public class BlockDestroyListener extends EventListener {

  public BlockDestroyListener(BlockLockerPluginImpl plugin) {
    super(plugin);
  }

  private Optional<ProtectionSign> asMainSign(Block block) {
    Material material = block.getType();
    if (!Tag.WALL_SIGNS.isTagged(material) && !Tag.STANDING_SIGNS.isTagged(material)) {
      return Optional.empty();
    }

    Optional<ProtectionSign> protectionSign = plugin.getSignParser().parseSign(block);
    if (!protectionSign.isPresent()) {
      return Optional.empty();
    }
    if (!protectionSign.get().getType().isMainSign()) {
      return Optional.empty();
    }
    return protectionSign;
  }

  private Optional<ProtectionSign> findMainSignSupportedBy(Protection protection, Block block) {
    for (ProtectionSign sign : protection.getSigns()) {
      if (!sign.getType().isMainSign()) {
        continue;
      }
      Block signBlock = sign.getLocation().getBlock();
      if (isSupportedBy(signBlock, block)) {
        return Optional.of(sign);
      }
    }
    return Optional.empty();
  }

  private boolean isSupportedBy(Block signBlock, Block potentialSupport) {
    if (Tag.WALL_SIGNS.isTagged(signBlock.getType())) {
      if (signBlock.getBlockData() instanceof Directional) {
        Directional directional = (Directional) signBlock.getBlockData();
        return signBlock
            .getRelative(directional.getFacing().getOppositeFace())
            .equals(potentialSupport);
      }
    } else if (Tag.STANDING_SIGNS.isTagged(signBlock.getType())) {
      return signBlock.getRelative(BlockFace.DOWN).equals(potentialSupport);
    }
    return false;
  }

  private void destroyOtherSigns(ProtectionSign protectionSign, Protection protection) {
    for (ProtectionSign foundSign : protection.getSigns()) {
      if (!foundSign.equals(protectionSign)) {
        foundSign.getLocation().getBlock().breakNaturally();
      }
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    Block block = event.getBlock();
    Optional<Protection> protection = plugin.getProtectionFinder().findProtection(block);
    if (!protection.isPresent()) {
      return;
    }

    Player player = event.getPlayer();
    Profile profile = plugin.getProfileFactory().fromPlayer(player);
    if (!protection.get().isOwner(profile)) {
      if (player.hasPermission(Permissions.CAN_ADMIN)) {
        String ownerName = protection.get().getOwnerDisplayName();
        plugin.getTranslator().sendMessage(player, Translation.PROTECTION_BYPASSED, ownerName);
      } else if (isExpired(protection.get())) {
        plugin.getTranslator().sendMessage(player, Translation.PROTECTION_EXPIRED);
      } else {
        event.setCancelled(true);
        return;
      }
    }

    Optional<ProtectionSign> mainSign = asMainSign(block);
    if (!mainSign.isPresent()) {
      // Maybe we are breaking the block supporting the main sign?
      mainSign = findMainSignSupportedBy(protection.get(), block);
    }

    if (mainSign.isPresent()) {
      // Decrement team count
      nl.rutgerkok.blocklocker.impl.ProtectionLimitManager limitManager =
          plugin.getProtectionLimitManager();
      if (limitManager != null) {
        String teamName = limitManager.getTeamFromProtection(protection.get());
        if (teamName != null) {
          limitManager.changeTeamCount(teamName, -1);
          teamLimitCount(player, teamName);
        }
      }
      destroyOtherSigns(mainSign.get(), protection.get());
    }
  }

  private void teamLimitCount(Player player, String teamName) {
    Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
    Team team = board.getTeam(teamName);

    if (team == null) {
      player.sendMessage(ChatColor.RED + "팀 '" + teamName + "'을(를) 찾을 수 없습니다.");
      return;
    }

    ProtectionLimitManager limitManager = plugin.getProtectionLimitManager();
    if (limitManager == null) {
      player.sendMessage(ChatColor.RED + "보호 제한이 비활성화되어 있습니다.");
      return;
    }

    int limit = limitManager.getTeamLimit(team);
    int count = limitManager.getTeamProtectionCount(team);
    String limitStr = limit < 0 ? "무제한" : String.valueOf(limit);

    player.sendMessage(
        ChatColor.YELLOW
            + "팀 "
            + teamName
            + "의 보호 블록: "
            + ChatColor.WHITE
            + count
            + ChatColor.GRAY
            + "/"
            + ChatColor.WHITE
            + limitStr);
  }

  @EventHandler(ignoreCancelled = true)
  public void onBlockBurnEvent(BlockBurnEvent event) {
    if (plugin.getChestSettings().allowDestroyBy(AttackType.FIRE)) {
      return;
    }
    if (isProtected(event.getBlock())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onBlockExplodeEvent(BlockExplodeEvent event) {
    // Generally caused by a Bed, but when the event is triggered the bed is no
    // longer there so we
    // can't check that
    AttackType attackType = AttackType.BLOCK_EXPLOSION;
    if (plugin.getChestSettings().allowDestroyBy(attackType)) {
      return;
    }
    for (Iterator<Block> it = event.blockList().iterator(); it.hasNext(); ) {
      Block block = it.next();
      if (isProtected(block)) {
        it.remove();
      }
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onBlockPistonExtend(BlockPistonExtendEvent event) {
    if (plugin.getChestSettings().allowDestroyBy(AttackType.PISTON)) {
      return;
    }
    if (anyProtected(event.getBlocks())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onBlockPistonRetract(BlockPistonRetractEvent event) {
    if (plugin.getChestSettings().allowDestroyBy(AttackType.PISTON)) {
      return;
    }
    if (event.isSticky() && anyProtected(event.getBlocks())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onEntityChangeBlock(EntityChangeBlockEvent event) {
    AttackType attackType = AttackType.UNKNOWN;
    if (event instanceof EntityBreakDoorEvent) {
      attackType = AttackType.ZOMBIE;
    } else if (event.getEntity() instanceof Enderman) {
      attackType = AttackType.ENDERMAN;
    }
    if (plugin.getChestSettings().allowDestroyBy(attackType)) {
      return;
    }
    if (isProtected(event.getBlock())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onEntityExplodeEvent(EntityExplodeEvent event) {
    AttackType attackType = AttackType.UNKNOWN;
    Entity attacker = event.getEntity();
    if (attacker instanceof TNTPrimed) {
      attackType = AttackType.TNT;
    } else if (attacker instanceof Creeper) {
      attackType = AttackType.CREEPER;
    } else if (attacker instanceof Fireball) {
      if (((Fireball) attacker).getShooter() instanceof Ghast) {
        attackType = AttackType.GHAST;
      }
    }
    if (plugin.getChestSettings().allowDestroyBy(attackType)) {
      return;
    }
    for (Iterator<Block> it = event.blockList().iterator(); it.hasNext(); ) {
      Block block = it.next();
      if (isProtected(block)) {
        it.remove();
      }
    }
  }

  @EventHandler
  public void onRedstone(BlockRedstoneEvent event) {
    if (event.getNewCurrent() == event.getOldCurrent()) {
      return;
    }

    if (isRedstoneDenied(event.getBlock())) {
      event.setNewCurrent(event.getOldCurrent());
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onStructureGrow(StructureGrowEvent event) {
    if (plugin.getChestSettings().allowDestroyBy(AttackType.SAPLING)) {
      return;
    }
    // Check deleted blocks
    List<BlockState> blocks = event.getBlocks();
    for (Iterator<BlockState> it = blocks.iterator(); it.hasNext(); ) {
      BlockState blockState = it.next();

      if (blockState.getType() == Material.AIR) {
        // Almost all replaced blocks are air, so this is a cheap,
        // zero-allocation way out
        continue;
      }

      if (isProtected(blockState.getBlock())) {
        event.setCancelled(true);
        return;
      }
    }
  }
}
