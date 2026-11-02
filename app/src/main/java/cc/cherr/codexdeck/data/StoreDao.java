package cc.cherr.codexdeck.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Upsert;

import java.util.List;

@Dao
public interface StoreDao {
    @Query("SELECT * FROM hosts ORDER BY favorite DESC, lastUsedAt DESC, label COLLATE NOCASE")
    List<HostRecord> hosts();

    @Query("SELECT * FROM hosts WHERE id = :id")
    HostRecord host(String id);

    @Upsert
    void saveHost(HostRecord host);

    @Query("DELETE FROM hosts WHERE id = :id")
    void deleteHost(String id);

    @Query("UPDATE hosts SET favorite = NOT favorite, updatedAt = :timestamp WHERE id = :id")
    void toggleFavorite(String id, long timestamp);

    @Query("UPDATE hosts SET lastUsedAt = MAX(lastUsedAt, :timestamp) WHERE id = :id")
    void markHostUsed(String id, long timestamp);

    @Query("SELECT * FROM identities ORDER BY label COLLATE NOCASE")
    List<IdentityRecord> identities();

    @Query("SELECT * FROM identities WHERE id = :id")
    IdentityRecord identity(String id);

    @Insert
    void insertIdentity(IdentityRecord identity);

    @Upsert
    void saveIdentity(IdentityRecord identity);

    @Query("UPDATE identities SET publicKey = :publicKey WHERE id = :id")
    void savePublicKey(String id, String publicKey);

    @Query("DELETE FROM identities WHERE id = :id")
    void deleteIdentity(String id);

    @Query("SELECT COUNT(*) FROM hosts WHERE identityId = :id")
    int identityUsers(String id);

    @Query("SELECT * FROM known_hosts WHERE hostname = :hostname AND port = :port")
    KnownHostRecord knownHost(String hostname, int port);

    @Upsert
    void saveKnownHost(KnownHostRecord host);

    @Upsert
    void saveCachedThreads(List<CachedThreadRecord> threads);

    @Query("SELECT * FROM cached_threads WHERE hostId = :hostId ORDER BY updatedAt DESC")
    List<CachedThreadRecord> cachedThreads(String hostId);

    @Query("DELETE FROM cached_threads WHERE hostId = :hostId")
    void deleteCachedThreads(String hostId);
}
