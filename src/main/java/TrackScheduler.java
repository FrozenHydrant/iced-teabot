import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.MessageChannel;

import java.util.*;

public final class TrackScheduler extends AudioEventAdapter implements AudioLoadResultHandler {
    private static Map<Snowflake, GuildAudioManager> GUILD_AUDIOS;
    private final Snowflake snowflake;
    private final Guild guild;
    private final AudioPlayer player;
    private final LinkedList<AudioTrack> currentTracks = new LinkedList<>();
    public final HashMap<Snowflake, Integer> amountQueuedByUser = new HashMap<>();
    private int playlistLoadCount;
    private String playlistLoadDirection;
    private Snowflake senderMostRecent;
    public TrackScheduler(final AudioPlayer player, final Map<Snowflake, GuildAudioManager> GUILD_AUDIOS, final Snowflake snowflake, final Guild guild) {
        this.player = player;
        this.snowflake = snowflake;
        this.playlistLoadCount = Integer.MAX_VALUE;
        this.playlistLoadDirection = "front";
        this.guild = guild;
        if (TrackScheduler.GUILD_AUDIOS == null) {
            TrackScheduler.GUILD_AUDIOS = GUILD_AUDIOS;
        }
    }

    /*
     Returns a (deep) copy of the amounts queued by user.
    */
    /*
    public HashMap<Snowflake, Integer> getAmountsQueued() {
        HashMap<>
    }
    */
    public void setPlaylistLoadCount(int playlistLoadCount) {
        this.playlistLoadCount = playlistLoadCount;
    }

    public void updateSenderMostRecent(User user) {
        this.senderMostRecent = user.getId();
    }

    public void setPlaylistLoadDirection(String playlistLoadDirection) {
        this.playlistLoadDirection = playlistLoadDirection;
    }

    public int queueSize() {
        return currentTracks.size();
    }

    public List<AudioTrack> songList() {
        LinkedList<AudioTrack> toReturn = new LinkedList<>();
        for (AudioTrack song: currentTracks) {
            toReturn.add(song.makeClone());
        }
        return toReturn;
    }

    public static String getAuthorFromId(Guild guild, AdditionalSongInfo additionalSongInfo) {
        Snowflake userId = additionalSongInfo.requesterId;
        Member member = guild.getMemberById(userId).block();
        if (member != null) {
            return member.getDisplayName();
        }
        return "Unknown";
    }

    public String songListString(int page) {
        final StringBuilder songList = new StringBuilder();
        final int bottomBound = (page-1)*10;
        final int tracksSize = currentTracks.size();
        int count = (page-1) * 10;
        for (AudioTrack t : currentTracks.subList(Math.min(bottomBound, tracksSize), Math.min(bottomBound+10, tracksSize))) {
            count++;
            StringBuilder addition = new StringBuilder();
            final String userName = getAuthorFromId(guild, (AdditionalSongInfo) t.getUserData());
            addition.append("**")
                    .append(count)
                    .append("** - ")
                    .append(t.getInfo().title)
                    .append("  |  **")
                    .append(userName)
                    .append("**\n\n");

            songList.append(addition);
        }
        if (bottomBound+10 < tracksSize) {
            songList.append("*and more...*\n");
        }
        return songList.toString();
    }

    @Override
    public void trackLoaded(final AudioTrack track) {
        // LavaPlayer found an audio source for us to play
        track.setUserData(new AdditionalSongInfo(senderMostRecent));
        final boolean isPlaying = player.startTrack(track, true);
        final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
        if (!isPlaying) {
            loadTrack(track);
            channel.createMessage("**Added `" + track.getInfo().title + "` to the queue.**").block();
        }
    }

    public void skipSong() {
        final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
        if (currentTracks.size() > 0) {
            channel.createMessage("**Skipped song. Now playing:**").block();
            final AudioTrack toPlay = currentTracks.remove(0);
            final Snowflake userId = ((AdditionalSongInfo)toPlay.getUserData()).requesterId;
            amountQueuedByUser.put(userId, amountQueuedByUser.getOrDefault(userId, 1) - 1);
            player.startTrack(toPlay, false);
        } else {
            player.stopTrack();
            channel.createMessage("**Skipped song. No song is currently playing.**").block();
        }
    }

    private void loadTrack(AudioTrack t) {
        t.setUserData(new AdditionalSongInfo(senderMostRecent));
        amountQueuedByUser.put(senderMostRecent, amountQueuedByUser.getOrDefault(senderMostRecent, 0) + 1);
        currentTracks.add(t);
    }

    /*
     Ensures that every user has amount songs enqueued.
     */
    public void equalize(int amount) {
        final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
        final HashMap<Snowflake, List<AudioTrack>> userTracks = new HashMap<>();
        for (AudioTrack audioTrack: currentTracks) {
            final Snowflake userId = ((AdditionalSongInfo)audioTrack.getUserData()).requesterId;
            if (!userTracks.containsKey(userId)) {
                userTracks.put(userId, new ArrayList<>());
            }
            userTracks.get(userId).add(audioTrack);
        }

        for (List<AudioTrack> audioTracks: userTracks.values()) {
            final List<AudioTrack> toRemove = audioTracks.subList(Math.min(amount, audioTracks.size()), audioTracks.size());
            for (AudioTrack audioTrack: toRemove) {
                final Snowflake userId = ((AdditionalSongInfo)audioTrack.getUserData()).requesterId;
                amountQueuedByUser.put(userId, amountQueuedByUser.getOrDefault(userId, 1) - 1);
                currentTracks.remove(audioTrack);
            }
        }

        //TODO: actually make this embed good
        channel.createEmbed(spec -> spec.setTitle("Successfully Trimmed Queue").setColor(Bot.THEME_COLOR)).block();
    }

    public void infolize() {

    }

    @Override
    public void playlistLoaded(final AudioPlaylist playlist) {
        // LavaPlayer found multiple AudioTracks from some playlist
        final List<AudioTrack> songsToLoad = playlist.getTracks();
        final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
        long durationCount = 0;
        final int insertedIndex = queueSize() + 1;
        String initialUri = "";
        switch (playlistLoadDirection) {
            case "front" -> {
                AudioTrack track = songsToLoad.get(0);
                track.setUserData(new AdditionalSongInfo(senderMostRecent));
                final boolean isPlaying = player.startTrack(track, true);
                final int start = isPlaying ? 1 : 0;
                for (int i = start; i < Math.min(songsToLoad.size(), playlistLoadCount); i++) {
                    AudioTrack song = songsToLoad.get(i);
                    durationCount += song.getDuration();
                    loadTrack(song);
                    if (initialUri.equals("")) {
                        initialUri = song.getInfo().uri;
                    }
                }
            }
            case "back" -> {
                AudioTrack track = songsToLoad.get(songsToLoad.size() - 1);
                track.setUserData(new AdditionalSongInfo(senderMostRecent));
                final boolean isPlaying = player.startTrack(track, true);
                final int start = isPlaying ? 1 : 0;
                int count = start;
                for (int i = songsToLoad.size() - 1 - start; i > -1; i--) {
                    AudioTrack song = songsToLoad.get(i);
                    durationCount += song.getDuration();
                    loadTrack(song);
                    if (initialUri.equals("")) {
                        initialUri = song.getInfo().uri;
                    }
                    count++;
                    if (count >= playlistLoadCount) {
                        break;
                    }
                }
            }
            case "random" -> {
                Collections.shuffle(songsToLoad);
                AudioTrack track = songsToLoad.get(0);
                track.setUserData(new AdditionalSongInfo(senderMostRecent));
                final boolean isPlaying = player.startTrack(track, true);
                final int start = isPlaying ? 1 : 0;
                for (int i = start; i < Math.min(songsToLoad.size(), playlistLoadCount); i++) {
                    AudioTrack song = songsToLoad.get(i);
                    durationCount += song.getDuration();
                    loadTrack(song);
                    if (initialUri.equals("")) {
                        initialUri = song.getInfo().uri;
                    }
                }
            }
        }
        final long totalDuration = durationCount / 1000;
        final String displayUri = initialUri;
        channel.createEmbed(spec -> spec.setTitle("Successfully Added Songs To Queue").setColor(Bot.THEME_COLOR).setThumbnail("https://i.ytimg.com/vi/" + displayUri.substring(displayUri.indexOf("=") + 1) + "/hq720.jpg").addField("Amount", Integer.toString(Math.min(songsToLoad.size(), playlistLoadCount)), false).addField("Length", Bot.generateTimeString(totalDuration), true).addField("Direction", playlistLoadDirection, true).addField("Position in Queue", Integer.toString(insertedIndex), true)).block();
    }

    @Override
    public void noMatches() {
        //System.out.println("no matches");
        // LavaPlayer did not find any audio to extract
    }

    public void shuffle() {
        Collections.shuffle(currentTracks);
    }

    @Override
    public void loadFailed(final FriendlyException exception) {
        System.out.println("load failed " + exception.getCause());
        // LavaPlayer could not parse an audio source for some reason
    }

    public void clearTracks() {
        amountQueuedByUser.clear();
        currentTracks.clear();
    }

    public int clearDupes() {
        final HashSet<String> uniqueSongs = new HashSet<>();
        int count = 0;
        for (int i = 0; i < currentTracks.size(); i++) {
            if (!uniqueSongs.add(currentTracks.get(i).getInfo().identifier)) {
                final AudioTrack removed = currentTracks.remove(i);
                final Snowflake userId = ((AdditionalSongInfo)removed.getUserData()).requesterId;
                amountQueuedByUser.put(userId, amountQueuedByUser.getOrDefault(userId, 1) - 1);
                count++;
                i--;
            }
        }
        return count;
    }

    public void stopSong() {
        player.stopTrack();
    }

    public void startSongOrNextSongInQueue() {
        if (getCurrentSong() != null) {
            player.startTrack(getCurrentSong(), true);
            return;
        }
        if (currentTracks.size() > 0) {
            final AudioTrack removed = currentTracks.remove(0);
            final Snowflake userId = ((AdditionalSongInfo)removed.getUserData()).requesterId;
            amountQueuedByUser.put(userId, amountQueuedByUser.getOrDefault(userId, 1) - 1);
            player.startTrack(removed, true);
        }
    }

    public AudioTrack getCurrentSong() {
        return player.getPlayingTrack();
    }

    @Override
    public void onTrackEnd(final AudioPlayer player, final AudioTrack track, final AudioTrackEndReason endReason) {
        //System.out.println("deciding what to do at the end of time.");
        if (endReason.mayStartNext) {
            //System.out.println("Song ended normally. Starting next track (if in existence)");
            if (GUILD_AUDIOS.get(snowflake).isSingleLooping) {
                //System.out.println("loop this track.");
                player.startTrack(track.makeClone(), false);
            } else {
                if (currentTracks.size() > 0) {
                    final AudioTrack removed = currentTracks.remove(0);
                    player.startTrack(removed, false);
                    if (GUILD_AUDIOS.get(snowflake).isQueueLooping) {
                        currentTracks.add(track.makeClone());
                    } else {
                        final Snowflake userId = ((AdditionalSongInfo)removed.getUserData()).requesterId;
                        amountQueuedByUser.put(userId, amountQueuedByUser.getOrDefault(userId, 1) - 1);
                    }
                }
            }
        }


        //System.out.println("Not starting new track. Reason: " + endReason.name());
        //
        // endReason == FINISHED: A track finished or died by an exception (mayStartNext = true).
        // endReason == LOAD_FAILED: Loading of a track failed (mayStartNext = true).
        // endReason == STOPPED: The player was stopped.
        // endReason == REPLACED: Another track started playing while this had not finished
        // endReason == CLEANUP: Player hasn't been queried for a while, if you want you can put a
        //                       clone of this back to your queue
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        //System.out.println("New track started");
        final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
        final String uri = track.getInfo().uri;
        final String thumbnailUri = uri.contains("soundcloud.com") ? "https://developers.soundcloud.com/assets/logo_big_white-65c2b096da68dd533db18b9f07d14054.png" : "https://i.ytimg.com/vi/" + uri.substring(uri.indexOf("=") + 1) + "/hq720.jpg";
        final String authorUri = uri.contains("soundcloud.com") ? uri.substring(0, uri.lastIndexOf("/")) : "";
        final String requesterName = getAuthorFromId(guild, (AdditionalSongInfo) track.getUserData());
        channel.createEmbed(spec -> spec.setColor(Bot.THEME_COLOR).setTitle("**" + track.getInfo().title + "**").setUrl(uri).setThumbnail(thumbnailUri).addField("Requested By", requesterName, false).setAuthor(track.getInfo().author, authorUri, "")).block();
    }

    @Override
    public void onPlayerPause(AudioPlayer player) {
        //System.out.println("player paused");
    }
}