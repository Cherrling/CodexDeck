package cc.cherr.codexdeck.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(
    entities = {
        HostRecord.class,
        IdentityRecord.class,
        KnownHostRecord.class,
        CachedThreadRecord.class
    },
    version = 1,
    exportSchema = true
)
public abstract class CodexDeckDatabase extends RoomDatabase {
    public abstract StoreDao records();
}
