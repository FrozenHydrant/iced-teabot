
import discord4j.core.object.entity.Message;
import reactor.util.annotation.NonNull;

/*
 Contains info about the query as well as the song
*/
public final class AdditionalSongInfo {
    @NonNull
    public final Message message;
    public AdditionalSongInfo(@NonNull Message message) {
        this.message = message;
    }
}
