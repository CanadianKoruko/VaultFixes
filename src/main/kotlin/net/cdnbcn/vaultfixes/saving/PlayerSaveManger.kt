package net.cdnbcn.vaultfixes.saving

import iskallia.vault.world.data.VaultSnapshots
import net.cdnbcn.vaultfixes.VaultFixes
import net.cdnbcn.vaultfixes.data.TemporalMapCache
import net.cdnbcn.vaultfixes.mixin_interfaces.saving.IVaultPlayerData
import net.cdnbcn.vaultfixes.mixin_interfaces.saving.IVaultPlayerDataRW
import net.cdnbcn.vaultfixes.mixin_interfaces.saving.VaultSnapshotsMixinInterface
import net.minecraft.nbt.*
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
import thedarkcolour.kotlinforforge.forge.FORGE_BUS
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.collections.ArrayList
import kotlin.io.path.createDirectories


object PlayerSaveManger {
    private lateinit var PlayerDataFolder: Path
    private val offlinePlayerMap = TemporalMapCache<UUID, VaultPlayerData>(60, {}, { data -> saveDataToDisc(data) })
    private val onlinePlayerMap = mutableMapOf<UUID, VaultPlayerData>()

    @JvmStatic
    internal fun initialize() {
        PlayerDataFolder = VaultFixes.dataDir.resolve("playerdata").createDirectories()
        if(VaultFixes.newDataStructureEnabled) {
            FORGE_BUS.addListener(this::onPlayerJoin)
            FORGE_BUS.addListener(this::onPlayerLeave)
        }
    }

    @JvmStatic
    fun getPlayerData(playerId: UUID): IVaultPlayerData {
        return onlinePlayerMap.getOrElse(playerId) {
            synchronized(offlinePlayerMap) {
                return@getOrElse offlinePlayerMap.getOrPut(playerId) {
                    val offlinePlayerData = VaultPlayerData(playerId)
                    readDataFromDisc(offlinePlayerData)
                    offlinePlayerData
                }
            }
        }
    }


    //region events
    @JvmStatic
    private fun onPlayerJoin(event: PlayerLoggedInEvent) {
        val serverPlayer = event.player as ServerPlayer

        try {
            // load player data, from offlineCache else from disc
            val offlineData = offlinePlayerMap.remove(serverPlayer.uuid)
            onlinePlayerMap[serverPlayer.uuid] =
                if (offlineData != null)
                    offlineData
                else {
                    val data = VaultPlayerData(serverPlayer.uuid)
                    readDataFromDisc(data)
                    data
                }
        } catch (ex: Exception) {
            VaultFixes.logger.error("Failed onPlayerJoin For: ${serverPlayer.uuid}", ex)
        }
    }
    @JvmStatic
    private fun onPlayerLeave(event: PlayerLoggedOutEvent) {
        val serverPlayer = event.player as ServerPlayer

        try {
            val data = onlinePlayerMap.remove(serverPlayer.uuid)!!
            saveDataToDisc(data)
            offlinePlayerMap[serverPlayer.uuid] = data
        } catch (ex: Exception) {
            VaultFixes.logger.error("Failed onPlayerLeave For: ${serverPlayer.uuid}", ex)
        }
    }
    @JvmStatic
    internal fun notifyPlayerSaving(player: ServerPlayer) {
        try {
            val data = getPlayerData(player.uuid) // get data by store not by player
            if (data.`vaultFixes$isDirty`())
                saveDataToDisc(data as IVaultPlayerDataRW)
        } catch (ex: Exception) {
            VaultFixes.logger.error("Failed notifyPlayerSaving For: ${player.uuid}", ex)
        }
    }
    //endregion events

    //region Data Management
    private fun readDataFromDisc(data: IVaultPlayerDataRW) {
        VaultFixes.logger.debug("Loading VaultPlayerData: {}", data.`vaultFixes$getPlayerUUID`())
        loadData(readNbt(data.`vaultFixes$getPlayerUUID`()), data)
    }
    @JvmStatic
    private fun saveDataToDisc(data: IVaultPlayerDataRW){
        VaultFixes.logger.debug("Saving VaultPlayerData: {}", data.`vaultFixes$getPlayerUUID`())
        writeNbt(data.`vaultFixes$getPlayerUUID`(), saveData(data))
    }
    private fun getFileLoc(playerId: UUID) : File {
        return PlayerDataFolder.resolve("$playerId.nbt.dat").toFile()
    }
    private fun readNbt(playerId: UUID) : CompoundTag {
        val file = getFileLoc(playerId)
        return if(file.exists())
            NbtIo.readCompressed(file)
        else
            CompoundTag()
    }
    private fun writeNbt(playerId: UUID, tag: CompoundTag) {
        val file = getFileLoc(playerId)
        NbtIo.writeCompressed(tag, file)
    }
    private fun loadData(tag: CompoundTag, data: IVaultPlayerDataRW) {
        val uuid = data.`vaultFixes$getPlayerUUID`()
        val vaultSnapshots = VaultSnapshots.get(VaultFixes.server) as VaultSnapshotsMixinInterface

        @Suppress("NAME_SHADOWING")
        val tag =
            if(!tag.contains("version") || tag.getInt("version") != 1)
                CompoundTag() // wipe save data if version mis-match
            else
                tag

        data.`vaultFixes$setSnapshots`(
            if(tag.contains("snapshots"))
                (tag["snapshots"] as ListTag).parallelStream()
                    .map(NbtUtils::loadUUID)
                    .collect(::ArrayList, ArrayList<UUID>::add, ArrayList<UUID>::addAll)
            else
                // TODO: don't query all vault snapshots if player is vault lvl0
                vaultSnapshots.`vaultFixes$compileAllForPlayer`(uuid)
        )
        data.`vaultFixes$markClean`()
    }
    private fun saveData(data: IVaultPlayerDataRW): CompoundTag {
        val tag = CompoundTag()

        tag.putInt("version", 1)
        tag.put("snapshots", data.`vaultFixes$getAllSnapshots`()
            .map(NbtUtils::createUUID)
            .collect(::ListTag, ListTag::add, ListTag::addAll)
        )

        data.`vaultFixes$markClean`()
        return tag
    }
    //endregion Data Management
}