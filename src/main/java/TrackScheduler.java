import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.rest.util.Color;

import java.util.*;

public final class TrackScheduler extends AudioEventAdapter implements AudioLoadResultHandler {
    private static Map<Snowflake, GuildAudioManager> GUILD_AUDIOS;
    private final Snowflake snowflake;
    private final AudioPlayer player;
    private final ArrayList<EnhancedSong> currentTracks = new ArrayList<>();
    private int playlistLoadCount;
    private String playlistLoadDirection;
    private Message mostRecentMessage;
    public TrackScheduler(final AudioPlayer player, final Map<Snowflake, GuildAudioManager> GUILD_AUDIOS, final Snowflake snowflake) {
        this.player = player;
        this.snowflake = snowflake;
        this.playlistLoadCount = Integer.MAX_VALUE;
        this.playlistLoadDirection = "front";
        if (TrackScheduler.GUILD_AUDIOS == null) {
            TrackScheduler.GUILD_AUDIOS = GUILD_AUDIOS;
        }
    }

    public void setPlaylistLoadCount(int playlistLoadCount) {
        this.playlistLoadCount = playlistLoadCount;
    }

    public void updateMostRecentMessage(Message message) {
        this.mostRecentMessage = message;
    }

    public void setPlaylistLoadDirection(String playlistLoadDirection) {
        this.playlistLoadDirection = playlistLoadDirection;
    }

    public int queueSize() {
        return currentTracks.size();
    }

    public String songList() {
        final StringBuilder songList = new StringBuilder();
        int count = 0;
        for (EnhancedSong t : currentTracks) {
            count++;
            StringBuilder addition = new StringBuilder();
            addition.append(count);
            addition.append(". ");
            addition.append(t.song.getInfo().title);
            addition.append("\n");
            if (songList.length() + addition.length() > 500) {
                songList.append("... and many others.");
                break;
            } else {
                songList.append(addition);
            }

        }
        return songList.toString();
    }

    @Override
    public void trackLoaded(final AudioTrack track) {
        // LavaPlayer found an audio source for us to play
        final boolean isPlaying = player.startTrack(track, true);
        final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
        if (!isPlaying) {
            loadTrack(track);
            channel.createMessage("**Added `" + track.getInfo().title + "` to the queue.**").block();
        }
    }

    public void skipSong() {
        if (currentTracks.size() > 0) {
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            channel.createMessage("**Skipped song.**").block();
            player.startTrack(currentTracks.remove(0).song, false);
        } else {
            player.stopTrack();
        }
    }

    private void loadTrack(AudioTrack t) {
        currentTracks.add(new EnhancedSong(mostRecentMessage, t));
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
                final boolean isPlaying = player.startTrack(songsToLoad.get(0), true);
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
                final boolean isPlaying = player.startTrack(songsToLoad.get(songsToLoad.size() - 1), true);
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
                final boolean isPlaying = player.startTrack(songsToLoad.get(0), true);
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
        channel.createEmbed(spec -> spec.setTitle("Successfully Added Songs To Queue").setColor(Color.RUST).setThumbnail("https://i.ytimg.com/vi/" + displayUri.substring(displayUri.indexOf("=") + 1) + "/hq720.jpg").addField("Amount", Integer.toString(Math.min(songsToLoad.size(), playlistLoadCount)), false).addField("Length", Bot.generateTimeString(totalDuration), true).addField("Direction", playlistLoadDirection, true).addField("Position in Queue", Integer.toString(insertedIndex), true)).block();
    }

    @Override
    public void noMatches() {
        // LavaPlayer did not find any audio to extract
    }

    public void shuffle() {
        Collections.shuffle(currentTracks);
    }

    @Override
    public void loadFailed(final FriendlyException exception) {
        // LavaPlayer could not parse an audio source for some reason
    }

    public void clearTracks() {
        currentTracks.clear();
    }

    public int clearDupes() {
        final HashSet<String> uniqueSongs = new HashSet<>();
        int count = 0;
        for (int i = 0; i < currentTracks.size(); i++) {
            if (!uniqueSongs.add(currentTracks.get(i).song.getInfo().uri)) {
                currentTracks.remove(i);
                count++;
                i--;
            }
        }
        return count;
    }

    public void stopSong() {
        player.stopTrack();
    }

    public void startNextSongInQueue() {
        if (currentTracks.size() > 0) {
            player.startTrack(currentTracks.remove(0).song, false);
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
                    player.startTrack(currentTracks.remove(0).song, false);
                    if (GUILD_AUDIOS.get(snowflake).isQueueLooping) {
                        currentTracks.add(new EnhancedSong(null, track.makeClone()));
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
        channel.createEmbed(spec -> spec.setColor(Color.RUST).setTitle("Now Playing: " + track.getInfo().title).setUrl(uri).setDescription("").setThumbnail("https://i.ytimg.com/vi/" + uri.substring(uri.indexOf("=") + 1) + "/hq720.jpg").addField("Author", track.getInfo().author, false)).block();
    }

    @Override
    public void onPlayerPause(AudioPlayer player) {
        //System.out.println("player paused");
    }
}