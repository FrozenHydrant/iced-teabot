import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import discord4j.common.ReactorResources;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.EventDispatcher;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.VoiceState;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.entity.channel.VoiceChannel;
import discord4j.rest.util.Color;
import discord4j.voice.VoiceConnection;
import io.github.cdimascio.dotenv.Dotenv;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class Bot {
    private static final Map<String, Command> COMMANDS = new HashMap<>();
    public static final AudioPlayerManager PLAYER_MANAGER = new DefaultAudioPlayerManager();
    public static final Map<Snowflake, GuildAudioManager> GUILD_AUDIOS = new HashMap<>();


    public static void main(final String[] args) {
        /*
         Setup player manager
        */
        PLAYER_MANAGER.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        AudioSourceManagers.registerRemoteSources(PLAYER_MANAGER);

        /*
         Make all commands here
        */
        COMMANDS.put("ping", event -> {
            final Guild guild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(guild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            channel.createMessage("**Pong!**").block();
        });
        COMMANDS.put("join", event -> {
            final Guild guild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(guild);
            final TrackScheduler trackScheduler = GUILD_AUDIOS.get(snowflake).scheduler;
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            if (event.getMember().isPresent()) {
                if (trackScheduler.queueSize() > 0) {
                    joinCall(snowflake, event.getMember().get());
                    trackScheduler.startNextSongInQueue();
                } else {
                    channel.createMessage("**Did not join the call because the queue was empty. Please queue some songs first by using %play.**").block();
                }
            }
        });
        COMMANDS.put("qloop", event -> {
            final Guild guild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(guild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            if (GUILD_AUDIOS.get(snowflake).isQueueLooping) {
                channel.createMessage("**Queue loop disabled.**").block();
                GUILD_AUDIOS.get(snowflake).isQueueLooping = false;
            } else {
                channel.createMessage("**Queue loop enabled.**").block();
                GUILD_AUDIOS.get(snowflake).isQueueLooping = true;
            }
        });
        COMMANDS.put("loop", event -> {
            final Guild guild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(guild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            if (GUILD_AUDIOS.get(snowflake).isSingleLooping) {
                channel.createMessage("**Song loop disabled.**").block();
                GUILD_AUDIOS.get(snowflake).isSingleLooping = false;
            } else {
                channel.createMessage("**Current song will now loop.**").block();
                GUILD_AUDIOS.get(snowflake).isSingleLooping = true;
            }
        });
        COMMANDS.put("dc", event -> {
            final Guild guild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(guild);
            leaveCall(snowflake);
        });
        COMMANDS.put("np", event -> {
            final Guild guild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(guild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            final TrackScheduler trackScheduler = GUILD_AUDIOS.get(snowflake).scheduler;
            final AudioTrack song = trackScheduler.getCurrentSong();
            if (song != null) {
                final long position = song.getPosition();
                final long duration = song.getDuration();
                final String tidiedDesc = (position / 60000) + ":" + (position / 1000 % 60 < 10 ? "0" : "") + (position / 1000 % 60) + " / " + (duration / 60000) + ":" + (duration / 1000 % 60 < 10 ? "0" : "") + (duration / 1000 % 60);
                final String uri = song.getInfo().uri;
                channel.createEmbed(spec -> spec.setColor(Color.RUST).setTitle("Now Playing: " + song.getInfo().title).setUrl(uri).setDescription(tidiedDesc).setThumbnail("https://i.ytimg.com/vi/" + uri.substring(uri.indexOf("=") + 1) + "/hq720.jpg").addField("Author", song.getInfo().author, false)).block();
            } else {
                channel.createMessage("**No track is currently playing.**").block();
            }
        });
        COMMANDS.put("shuffle", event -> {
            final Guild currentGuild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final TrackScheduler trackScheduler = GUILD_AUDIOS.get(snowflake).scheduler;
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            trackScheduler.shuffle();
            channel.createMessage("**Shuffled playlist.**").block();
        });
        COMMANDS.put("skip", event -> {
            final Guild currentGuild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final TrackScheduler trackScheduler = GUILD_AUDIOS.get(snowflake).scheduler;
            trackScheduler.skipSong();
        });
        COMMANDS.put("clear", event -> {
            final Guild guild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(guild);
            final TrackScheduler trackScheduler = GUILD_AUDIOS.get(snowflake).scheduler;
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            channel.createMessage("**Cleared queue.**").block();
            trackScheduler.clearTracks();
        });
        COMMANDS.put("p", event -> {
            final Guild guild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(guild);
            final TrackScheduler trackScheduler = GUILD_AUDIOS.get(snowflake).scheduler;
            final Message message = event.getMessage();
            final String content = message.getContent();
            if (content.contains(" ")) {
                PLAYER_MANAGER.loadItem(content.substring(content.indexOf(" ") + 1), trackScheduler);
                if (event.getMember().isPresent()) {
                    joinCall(snowflake, event.getMember().get());
                }
            }
        });
        COMMANDS.put("q", event -> {
            final Guild currentGuild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            final TrackScheduler trackScheduler = GUILD_AUDIOS.get(snowflake).scheduler;
            final AudioTrack song = trackScheduler.getCurrentSong();
            final String messageToBeSent = trackScheduler.songList();
            if (messageToBeSent.length() > 0) {
                if (song != null) {
                    final String uri = song.getInfo().uri;
                    channel.createEmbed(spec -> spec.setColor(Color.RUST).setTitle("Queue").addField("Current Song", song.getInfo().title, false).setUrl(uri).setThumbnail("https://i.ytimg.com/vi/" + uri.substring(uri.indexOf("=") + 1) + "/hq720.jpg").addField("Queue", messageToBeSent, false).addField("Queue Size", Integer.toString(trackScheduler.queueSize()), false)).block();
                } else {
                    channel.createEmbed(spec -> spec.setColor(Color.RUST).setTitle("Queue").addField("Queue", messageToBeSent, false).addField("Queue Size", Integer.toString(trackScheduler.queueSize()), false)).block();
                }
            } else {
                channel.createMessage("**There are no songs in the queue.**").block();
            }
        });
        COMMANDS.put("help", event -> {
            final Guild guild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(guild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            final StringBuilder commandHelpString = new StringBuilder();
            for (String command : COMMANDS.keySet()) {
                commandHelpString.append(command);
                commandHelpString.append(", ");
            }
            commandHelpString.append(" are existing commands.");
            channel.createEmbed(spec -> spec.setColor(Color.RUST).setTitle("Help").addField("Available Commands", commandHelpString.toString(), false)).block();
        });
        COMMANDS.put("removedupes", event -> {
            final Guild guild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(guild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            final TrackScheduler trackScheduler = GUILD_AUDIOS.get(snowflake).scheduler;
            channel.createMessage("**Removed " + trackScheduler.clearDupes() + " duplicate songs from the queue.**").block();
        });

        /*
         Load env file, login in, etc.
        */
        Dotenv dotenv = Dotenv.load();
        final String token = dotenv.get("TOKEN");
        final DiscordClient client = DiscordClientBuilder.create(token).setReactorResources(ReactorResources.builder().httpClient(HttpClient.create().compress(true).keepAlive(false).followRedirect(true).secure()).build()).build();
        final GatewayDiscordClient gateway = client.login().block();
        System.out.println("go!");

        /*
         Get an iterator over all of our events, and handle them accordingly
        */
        assert gateway != null;
        final EventDispatcher myEventDispatcher = gateway.getEventDispatcher();
        final Iterable<MessageCreateEvent> myEvents = myEventDispatcher.on(MessageCreateEvent.class).toIterable();
        for (MessageCreateEvent event : myEvents) {
            final String messageContent = event.getMessage().getContent();
            final Guild guild = event.getGuild().block();
            final MessageChannel channel = event.getMessage().getChannel().block();
            Member messagingPerson = null;
            if (event.getMember().isPresent()) {
                messagingPerson = event.getMember().get();
            }

            //prevent processing of commands sent by other bots, or in private messages
            if (channel != null && messagingPerson != null && !messagingPerson.isBot() && messageContent.length() > 0 && guild != null) {
                if (messageContent.charAt(0) == '%') {
                    final Snowflake snowflake = getCurrentSnowflake(guild);
                    if (!GUILD_AUDIOS.containsKey(snowflake)) {
                        GUILD_AUDIOS.put(snowflake, new GuildAudioManager(PLAYER_MANAGER, GUILD_AUDIOS, snowflake));
                    }
                    GUILD_AUDIOS.get(snowflake).messageChannel = channel;
                    final int firstSpace = messageContent.indexOf(" ");
                    final String commandInQuestion = messageContent.substring(1, firstSpace == -1 ? messageContent.length() : firstSpace);
                    if (COMMANDS.containsKey(commandInQuestion)) {
                        COMMANDS.get(commandInQuestion).execute(event);
                    }
                }
            }
        }

        gateway.onDisconnect().block();
    }

    /*
     Gets the snowflake (id) of a given guild.
    */
    public static Snowflake getCurrentSnowflake(Guild thisGuild) {
        if (thisGuild != null) {
            return thisGuild.getId();
        } else {
            throw new NullPointerException("Guild is null");
        }
    }

    public static void joinCall(Snowflake snowflake, Member member) {
        final LavaPlayerAudioProvider currentProvider = GUILD_AUDIOS.get(snowflake).provider;
        final VoiceState voiceState = member.getVoiceState().block();

        /*
         Use the voice state to attempt to join a voice channel
        */
        if (voiceState != null) {
            final VoiceChannel currentVoiceChannel = voiceState.getChannel().block();
            if (currentVoiceChannel != null) {
                if (GUILD_AUDIOS.get(snowflake).voiceConnection == null) {
                    GUILD_AUDIOS.get(snowflake).voiceConnection = currentVoiceChannel.join(spec -> spec.setProvider(currentProvider)).retry(5).block(Duration.ofSeconds(5));
                }
            }
        }
    }

    public static void leaveCall(Snowflake snowflake) {
        final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
        final VoiceConnection voiceConnection = GUILD_AUDIOS.get(snowflake).voiceConnection;
        final TrackScheduler trackScheduler = GUILD_AUDIOS.get(snowflake).scheduler;
        if (voiceConnection != null) {
            if (trackScheduler.getCurrentSong() != null) {
                trackScheduler.stopSong();
            }
            voiceConnection.disconnect().block();
            channel.createMessage("**Disconnected successfully. Queue will be preserved. Use %clear to clear it. **").block();
            GUILD_AUDIOS.get(snowflake).voiceConnection = null;
        }
    }


    public interface Command {
        void execute(MessageCreateEvent event);
    }
}
