package cc.cherr.codexdeck.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(
    tableName = "cached_threads",
    primaryKeys = {"hostId", "threadId"},
    foreignKeys = @ForeignKey(
        entity = HostRecord.class,
        parentColumns = "id",
        childColumns = "hostId",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {@Index("hostId"), @Index("cwd")}
)
public class CachedThreadRecord {
    @NonNull public String hostId = "";
    @NonNull public String threadId = "";
    @NonNull public String title = "";
    @NonNull public String cwd = "";
    @NonNull public String preview = "";
    @NonNull public String status = "notLoaded";
    public long updatedAt = 0;
    public long lastOpenedAt = 0;
}
