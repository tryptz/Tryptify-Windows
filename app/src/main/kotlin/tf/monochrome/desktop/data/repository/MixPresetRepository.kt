package tf.monochrome.desktop.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tf.monochrome.desktop.audio.dsp.model.MixPreset
import tf.monochrome.desktop.audio.dsp.preset.BuiltInMixPresets
import tf.monochrome.desktop.data.db.dao.MixPresetDao
import tf.monochrome.desktop.data.db.entity.MixPresetEntity
import tf.monochrome.desktop.data.sync.SupabaseSyncRepository
import tf.monochrome.desktop.data.sync.SyncKind
import tf.monochrome.desktop.data.sync.SyncOp
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MixPresetRepository @Inject constructor(
    private val dao: MixPresetDao,
    private val supabaseSync: SupabaseSyncRepository,
) {
    fun getAllPresets(): Flow<List<MixPreset>> = dao.getAllPresets().map { entities ->
        BuiltInMixPresets.presets + entities.map { it.toDomain() }
    }

    suspend fun getPresetById(id: Long): MixPreset? =
        if (id < 0) BuiltInMixPresets.presets.find { it.id == id }
        else dao.getPresetById(id)?.toDomain()

    suspend fun savePreset(preset: MixPreset): Long {
        val entity = preset.toEntity()
        val rowId = dao.upsert(entity)
        // Queued on save rather than left for a manual sync, so a preset made
        // on the phone is already in the account by the time the tablet next
        // opens. The entity is re-read because upsert is what assigns the row
        // id, and the copy above still carries 0; the queue keys it on its
        // creation time, which is how the cloud knows it.
        dao.getPresetById(rowId)?.takeIf { it.isCustom }?.let {
            supabaseSync.queueChange(SyncKind.MIX_PRESET, it.createdAt.toString(), SyncOp.UPSERT)
        }
        return rowId
    }

    suspend fun deletePreset(id: Long) {
        // Built-in presets (negative ids) are read-only.
        if (id < 0) return
        // Read before deleting: the cloud knows this preset by its creation
        // time, which is only available while the row still exists.
        val createdAt = dao.getPresetById(id)?.createdAt
        dao.delete(id)
        if (createdAt != null) supabaseSync.queueChange(SyncKind.MIX_PRESET, createdAt.toString(), SyncOp.DELETE)
    }

    fun getPresetCount(): Flow<Int> = dao.getPresetCount()

    private fun MixPresetEntity.toDomain() = MixPreset(
        id = id,
        name = name,
        stateJson = stateJson,
        isCustom = isCustom,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun MixPreset.toEntity() = MixPresetEntity(
        id = id,
        name = name,
        stateJson = stateJson,
        isCustom = isCustom,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
