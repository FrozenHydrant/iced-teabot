import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.channel.MessageChannel;

import java.util.*;

public final class TrackScheduler extends AudioEventAdapter implements AudioLoadResultHandler {
    private static Map<Snowflake, GuildAudioManager> GUILD_AUDIOS;
    private final Snowflake id;
    private final AudioPlayer player;
    private final LinkedList<AudioTrack> currentTracks = new LinkedList<>();

    public TrackScheduler(final AudioPlayer player, final Map<Snowflake, GuildAudioManager> GUILD_AUDIOS, final Snowflake id) {
        this.player = player;
        this.id = id;
        if (TrackScheduler.GUILD_AUDIOS == null) {
            TrackScheduler.GUILD_AUDIOS = GUILD_AUDIOS;
        }
    }

    public int queueSize() {
        return currentTracks.size();
    }

    public String songList() {
        StringBuilder songList = new StringBuilder();
        int count = 0;
        for (AudioTrack t : currentTracks) {
            count++;
            StringBuilder addition = new StringBuilder();
            addition.append(count);
            addition.append(". ");
            addition.append(t.getInfo().title);
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
        //player.playTrack(track);
        boolean playing = player.startTrack(track, true);
        MessageChannel cmsc = GUILD_AUDIOS.get(id).msc;
        //System.out.println("track loaded");
        if (!playing) {
            //player.playTrack(track);
            if (loadTrack(track)) {
                cmsc.createMessage("**Added `" + track.getInfo().title + "` to the queue.**").toProcessor().block();
            } else {
                cmsc.createMessage("**Could not add `" + track.getInfo().title + "` to the queue, because the queue is too large! A maximum of 2000 songs are allowed in the queue.**").toProcessor().block();
            }
        } else {
            cmsc.createMessage("**Now playing: `" + track.getInfo().title + "`.**").toProcessor().block();
        }


    }

    public void skipSong() {
        if (currentTracks.size() > 0) {
            MessageChannel channel = GUILD_AUDIOS.get(id).msc;
            channel.createMessage("**Skipped song.**").block();
            player.startTrack(currentTracks.remove(0), false);
        } else {
            player.stopTrack();
        }
    }

    private boolean loadTrack(AudioTrack t) {
        if (currentTracks.size() < 2000) {
            currentTracks.add(t);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void playlistLoaded(final AudioPlaylist playlist) {
        // LavaPlayer found multiple AudioTracks from some playlist
        List<AudioTrack> temp = playlist.getTracks();
        MessageChannel cmsc = GUILD_AUDIOS.get(id).msc;
        boolean playing = player.startTrack(temp.get(0), true);
        int start = playing ? 1 : 0;
        for (int i = start; i < temp.size(); i++) {
            if (!loadTrack(temp.get(i))) {
                cmsc.createMessage("**Only added " + i + " songs to the queue, because the queue is at its maximum capacity.**").toProcessor().block();
                return;
            }
        }
        cmsc.createMessage("**Added " + temp.size() + " songs to the queue.**").toProcessor().block();


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
        //System.out.println("load failed");
    }

    public void clearTracks() {
        currentTracks.clear();
    }

    public int clearDupes() {
        HashSet<String> temp = new HashSet<>();
        int c = 0;
        for (int i = 0; i < currentTracks.size(); i++) {
            if (!temp.add(currentTracks.get(i).getInfo().uri)) {
                currentTracks.remove(i);
                c++;
                i--;
            }
        }
        return c;
    }

    public void stopSong() {
        player.stopTrack();
    }

    public void startNextSongInQueue() {
        if (currentTracks.size() > 0) {
            player.startTrack(currentTracks.remove(0), true);
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
            if (GUILD_AUDIOS.get(id).singlelooping) {
                //System.out.println("loop this track.");
                player.startTrack(track.makeClone(), false);
            } else {
                if (currentTracks.size() > 0) {
                    player.startTrack(currentTracks.remove(0), true);
                    if (GUILD_AUDIOS.get(id).queuelooping) {
                        currentTracks.add(track.makeClone());
                    }
                }
            }
        }
        //System.out.println("Not starting new track. Reason: " + endReason.name());


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
    }

    @Override
    public void onPlayerPause(AudioPlayer player) {
        //System.out.println("player paused");
    }
}