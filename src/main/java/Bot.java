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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class Bot {
    private static final Map<String, Command> COMMANDS = new HashMap<>();
    public static final AudioPlayerManager PLAYER_MANAGER = new DefaultAudioPlayerManager();
    public static final Map<Snowflake, GuildAudioManager> GUILD_AUDIOS = new HashMap<>();
    public static final Map<String, String> COMMAND_DESCRIPTIONS = new HashMap<>();

    public static void main(final String[] args) throws IOException {
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
                final long position = song.getPosition() / 1000;
                final long duration = song.getDuration() / 1000;

                final int amountCompleted = (int) (((double)position / duration)*10);
                final String progressThroughSongBuilder = ":blue_square:".repeat(Math.max(0, amountCompleted)) + ":white_medium_small_square:".repeat(Math.max(0, 10 - amountCompleted));
                final String tidiedDesc = progressThroughSongBuilder + "\n\n **" + generateTimeString(position) + "** / **" + generateTimeString(duration) + "**";
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
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            final Message message = event.getMessage();
            final String content = message.getContent();
            trackScheduler.setPlaylistLoadCount(Integer.MAX_VALUE);
            trackScheduler.setPlaylistLoadDirection("front");
            if (content.split(" ").length > 1) {
                final String[] arguments = content.split(" ");
                if (arguments.length > 3) {
                    try {
                        final String loadDirection = arguments[3];
                        if (!loadDirection.equals("front") && !loadDirection.equals("back") && !loadDirection.equals("random")) {
                            throw new IllegalArgumentException();
                        }
                        trackScheduler.setPlaylistLoadDirection(loadDirection);
                    } catch (IllegalArgumentException e) {
                        channel.createEmbed(spec -> spec.setColor(Color.RUST).setTitle("Error Executing Command").setDescription("**" + arguments[3] + "** is not a valid option for **direction**! This value should either be `front`, `back`, or `random`.")).block();
                        return;
                    }
                }
                if (arguments.length > 2) {
                    try {
                        final int loadCount = Integer.parseInt(arguments[2]);
                        if (loadCount < 1) {
                            throw new NumberFormatException();
                        }
                        trackScheduler.setPlaylistLoadCount(loadCount);
                    } catch (NumberFormatException e) {
                        channel.createEmbed(spec -> spec.setColor(Color.RUST).setTitle("Error Executing Command").setDescription("**" + arguments[2] + "** is not a valid number for **count**!")).block();
                        return;
                    }
                }
                trackScheduler.updateMostRecentMessage(message);
                PLAYER_MANAGER.loadItem(arguments[1], trackScheduler);
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
            final String content = event.getMessage().getContent();
            if(content.split(" ").length < 2) {
                final StringBuilder commandHelpString = new StringBuilder();
                for (String command : COMMANDS.keySet()) {
                    commandHelpString.append(command);
                    commandHelpString.append(", ");
                }
                commandHelpString.append(" are existing commands.");
                commandHelpString.append("\n\nTo get help with a specific command, type **%help <commandname>**.");
                channel.createEmbed(spec -> spec.setColor(Color.RUST).setTitle("Help").addField("Available Commands", commandHelpString.toString(), false)).block();
            } else {
                final String commandToHelpWith = content.split(" ")[1];
                if (COMMAND_DESCRIPTIONS.containsKey(commandToHelpWith)) {
                    channel.createEmbed(spec -> spec.setTitle("Help With Command **" + commandToHelpWith + "**").setDescription(COMMAND_DESCRIPTIONS.get(commandToHelpWith)).setColor(Color.RUST)).block();
                } else {
                    channel.createEmbed(spec -> spec.setTitle("Error Executing Command").setDescription("Sorry, we do not currently have resources for **" + commandToHelpWith + "**. Maybe ask the internet?").setColor(Color.RUST)).block();
                }
            }
        });
        COMMANDS.put("removedupes", event -> {
            final Guild guild = event.getGuild().block();
            final Snowflake snowflake = getCurrentSnowflake(guild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).messageChannel;
            final TrackScheduler trackScheduler = GUILD_AUDIOS.get(snowflake).scheduler;
            channel.createMessage("**Removed " + trackScheduler.clearDupes() + " duplicate songs from the queue.**").block();
        });

        /*
         Load env file, login, etc.
        */
        Dotenv dotenv = Dotenv.load();
        final String token = dotenv.get("TOKEN");
        final DiscordClient client = DiscordClientBuilder.create(token).setReactorResources(ReactorResources.builder().httpClient(HttpClient.create().compress(true).keepAlive(false).followRedirect(true).secure()).build()).build();
        final GatewayDiscordClient gateway = client.login().block();

        /*
         Construct documentation
        */
        final BufferedReader bufferedReader = new BufferedReader(new FileReader("documentation.txt"));
        String documentationLine = bufferedReader.readLine();
        String beingDocumented = null;
        StringBuilder documentationDescription = new StringBuilder();
        while (documentationLine != null) {
            if (COMMANDS.containsKey(documentationLine)) {
                if (beingDocumented != null) {
                    COMMAND_DESCRIPTIONS.put(beingDocumented, documentationDescription.toString());
                }
                beingDocumented = documentationLine;
                documentationDescription = new StringBuilder();
            } else {
                documentationDescription.append(documentationLine).append("\n");
            }
            documentationLine = bufferedReader.readLine();
        }
        if (beingDocumented != null) {
            COMMAND_DESCRIPTIONS.put(beingDocumented, documentationDescription.toString());
        }
        bufferedReader.close();

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
     Gets a number of seconds and converts it into the string with format:
     HH:MM:SS
    */
    public static String generateTimeString(long seconds) {
        final long hours = seconds / 3600;
        final long minutes = (seconds - hours*3600) / 60;
        seconds = seconds % 60;

        String stringMinutes = Long.toString(minutes);
        String stringSeconds = Long.toString(seconds);
        if(stringSeconds.length() < 2) {
            stringSeconds = "0" + stringSeconds;
        }
        if (hours == 0) {
            return stringMinutes + ":" + stringSeconds;
        }
        if(stringMinutes.length() < 2) {
            stringMinutes = "0" + stringMinutes;
        }
        return hours + ":" + stringMinutes + ":" + stringSeconds;
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
