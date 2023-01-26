import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class Bot {
    private static final Map<String, Command> commands = new HashMap<>();
    public static final AudioPlayerManager PLAYER_MANAGER = new DefaultAudioPlayerManager();
    public static final Map<Snowflake, GuildAudioManager> GUILD_AUDIOS = new HashMap<>();

    public static void main(final String[] args) {
        PLAYER_MANAGER.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        AudioSourceManagers.registerRemoteSources(PLAYER_MANAGER);

        commands.put("ping", event -> {
            //System.out.println("Doing stuff! ping!");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
            channel.createMessage("**Pong!**").block();
        });
        commands.put("join", event -> {
            //System.out.println("joining.");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            TrackScheduler ts = GUILD_AUDIOS.get(snowflake).scheduler;
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
            if (event.getMember().isPresent()) {
                if (ts.queueSize() > 0) {
                    joinCall(snowflake, event.getMember().get());
                    ts.startNextSongInQueue();
                } else {
                    channel.createMessage("**Did not join the call because the queue was empty. Please queue some songs first by using %play.**").block();
                }
            }
        });
        commands.put("qloop", event -> {
            //System.out.println("toggling qloop.");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
            if (GUILD_AUDIOS.get(snowflake).queuelooping) {
                channel.createMessage("**Queue loop disabled.**").block();
                GUILD_AUDIOS.get(snowflake).queuelooping = false;
            } else {
                channel.createMessage("**Queue loop enabled.**").block();
                GUILD_AUDIOS.get(snowflake).queuelooping = true;
            }
        });
        commands.put("loop", event -> {
            //System.out.println ("toggling loop.");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
            if (GUILD_AUDIOS.get(snowflake).singlelooping) {
                channel.createMessage("**Song loop disabled.**").block();
                GUILD_AUDIOS.get(snowflake).singlelooping = false;
            } else {
                channel.createMessage("**Current song will now loop.**").block();
                GUILD_AUDIOS.get(snowflake).singlelooping = true;
            }
        });
        commands.put("dc", event -> {
            //System.out.println("disconnecting.");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            leaveCall(snowflake);
        });
        commands.put("np", event -> {
            System.out.println("now playing called");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
            final TrackScheduler ts = GUILD_AUDIOS.get(snowflake).scheduler;
            AudioTrack currentTrack = ts.getCurrentSong();
            System.out.println("np in guild: " + currentGuild + "\n np called in channel: \n" + channel + " ts:" + ts);
            if (currentTrack != null) {
                final long cposition = currentTrack.getPosition();
                final long clength = currentTrack.getDuration();
                final String cdesc = (cposition / 60000) + ":" + (cposition / 1000 % 60 < 10 ? "0" : "") + (cposition / 1000 % 60) + " / " + (clength / 60000) + ":" + (clength / 1000 % 60 < 10 ? "0" : "") + (clength / 1000 % 60);
                final String curi = currentTrack.getInfo().uri;
                //System.out.println(curi);
                channel.createEmbed(spec ->
                        spec.setColor(Color.RUST).setTitle("Now Playing: " + currentTrack.getInfo().title).setUrl(curi).setDescription(cdesc).setThumbnail("https://i.ytimg.com/vi/" + curi.substring(curi.indexOf("=") + 1) + "/hq720.jpg").addField("Author", currentTrack.getInfo().author, false)
                ).block();
            } else {
                channel.createMessage("**No track is currently playing.**").block();
            }

        });
        commands.put("shuffle", event -> {
            //System.out.println("shuffle");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final TrackScheduler ts = GUILD_AUDIOS.get(snowflake).scheduler;
            ts.shuffle();
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
            channel.createMessage("**Shuffled playlist.**").block();
        });
        commands.put("skip", event -> {
            //System.out.println("skip");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final TrackScheduler ts = GUILD_AUDIOS.get(snowflake).scheduler;
            ts.skipSong();
            //final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
        });
        commands.put("clear", event -> {
            //System.out.println("cleared");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final TrackScheduler ts = GUILD_AUDIOS.get(snowflake).scheduler;
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
            channel.createMessage("**Cleared queue.**").block();
            ts.clearTracks();

        });
        commands.put("p", event -> {
            //System.out.println("playing");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final TrackScheduler ts = GUILD_AUDIOS.get(snowflake).scheduler;
            final Message message = event.getMessage();
            final String content = message.getContent();
            //System.out.println("playing " + content);
            if (content.contains(" ")) {
                //System.out.println(content.substring(content.indexOf(" ") + 1));
                PLAYER_MANAGER.loadItem(content.substring(content.indexOf(" ") + 1), ts);
                if (event.getMember().isPresent()) {
                    joinCall(snowflake, event.getMember().get());
                }
            }
        });
        commands.put("q", event -> {
            //System.out.println("queue");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final TrackScheduler ts = GUILD_AUDIOS.get(snowflake).scheduler;
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
            final AudioTrack currentTrack = ts.getCurrentSong();
            final String messageToBeSent = ts.songList();
            //channel.createMessage(messageToBeSent).block();
            if (messageToBeSent.length() > 0) {
                if (currentTrack != null) {
                    final String curi = currentTrack.getInfo().uri;
                    channel.createEmbed(spec ->
                            spec.setColor(Color.RUST).setTitle("Queue").addField("Current Song", currentTrack.getInfo().title, false).setUrl(curi).setThumbnail("https://i.ytimg.com/vi/" + curi.substring(curi.indexOf("=") + 1) + "/hq720.jpg").addField("Queue", messageToBeSent, false).addField("Queue Size", Integer.toString(ts.queueSize()), false)
                    ).block();
                } else {
                    channel.createEmbed(spec ->
                            spec.setColor(Color.RUST).setTitle("Queue").addField("Queue", messageToBeSent, false).addField("Queue Size", Integer.toString(ts.queueSize()), false)
                    ).block();
                }
            } else {
                channel.createMessage("**There are no songs in the queue.**").block();
            }


        });
        commands.put("help", event -> {
            //System.out.println("helping");
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
            StringBuilder s = new StringBuilder();
            for (String i : commands.keySet()) {
                s.append(i);
                s.append(", ");
            }
            s.append(" are existing commands.");
            //System.out.println("help\n" + s);
            channel.createEmbed(spec -> spec.setColor(Color.RUST).setTitle("Help").addField("Available Commands", s.toString(), false)).block();
        });
        commands.put("removedupes", event -> {
            Guild currentGuild = event.getGuild().block();
            Snowflake snowflake = getCurrentSnowflake(currentGuild);
            final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
            TrackScheduler ts = GUILD_AUDIOS.get(snowflake).scheduler;
            channel.createMessage("**Removed " + ts.clearDupes() + " duplicate songs from the queue.**").block();
        });

        Dotenv dotenv = Dotenv.load();
        final String token = dotenv.get("TOKEN");
        final DiscordClient client = DiscordClient.create(token);
        final GatewayDiscordClient gateway = client.login().block();
        System.out.println("go!");


        gateway.getEventDispatcher().on(MessageCreateEvent.class).subscribe(event -> {
            final String messageContent = event.getMessage().getContent();
            final Guild guildSentIn = event.getGuild().block();
            final MessageChannel currentChannel = event.getMessage().getChannel().block();
            Member messagingPerson = null;
            //System.out.println("Guild" + guildSentIn + " channel " + currentChannel);
            if (event.getMember().isPresent()) {
                messagingPerson = event.getMember().get();
            }
            if (currentChannel != null && messagingPerson != null && !messagingPerson.isBot() && messageContent.length() > 0 && guildSentIn != null) { //prevent processing of commands sent by other bots, or in private messages
                if (messageContent.charAt(0) == '%') {
                    final Snowflake snowflake = getCurrentSnowflake(guildSentIn);
                    if (!GUILD_AUDIOS.containsKey(snowflake)) {
                        GUILD_AUDIOS.put(snowflake, new GuildAudioManager(PLAYER_MANAGER, GUILD_AUDIOS, snowflake));
                    }
                    GUILD_AUDIOS.get(snowflake).msc = currentChannel;
                    int firstSpace = messageContent.indexOf(" ");
                    String commandInQuestion = messageContent.substring(1, firstSpace == -1 ? messageContent.length() : firstSpace);
                    //System.out.println("commandinquestion " + commandInQuestion);
                    if (commands.containsKey(commandInQuestion)) {
                        commands.get(commandInQuestion).execute(event);
                    }
                }
            }
        });


        gateway.onDisconnect().block();
    }

    public static Snowflake getCurrentSnowflake(Guild thisGuild) {
        if (thisGuild != null) {
            return thisGuild.getId();
        } else {
            throw new NullPointerException();
        }
    }

    public static void joinCall(Snowflake snowflake, Member member) {
        //boolean success = false;
        //final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
        //final MessageChannel messageChannel = event.getMessage().getChannel().block()

        final LavaPlayerAudioProvider currentProvider = GUILD_AUDIOS.get(snowflake).provider;
        //System.out.println(GUILD_AUDIOS);

        VoiceState currentVoiceState = member.getVoiceState().block();
        if (currentVoiceState != null) {
            VoiceChannel currentVoiceChannel = currentVoiceState.getChannel().block();
            if (currentVoiceChannel != null) {
                if (GUILD_AUDIOS.get(snowflake).vc == null) {
                    GUILD_AUDIOS.get(snowflake).vc = currentVoiceChannel.join(spec -> spec.setProvider(currentProvider)).retry(5).block(Duration.ofSeconds(5));
                }
            }
        }
    }

    public static void leaveCall(Snowflake snowflake) {
        final MessageChannel channel = GUILD_AUDIOS.get(snowflake).msc;
        VoiceConnection currentVc = GUILD_AUDIOS.get(snowflake).vc;
        TrackScheduler ts = GUILD_AUDIOS.get(snowflake).scheduler;
        //boolean success = false;
        if (currentVc != null) {
            if (ts.getCurrentSong() != null) {
                ts.stopSong();
            }
            currentVc.disconnect().block();
            //success = true;
            channel.createMessage("**Disconnected successfully. Queue will be preserved. Use %clear to clear it. **").toProcessor().block();
            GUILD_AUDIOS.get(snowflake).vc = null;
        }
    }



    public interface Command {
        void execute(MessageCreateEvent event);
    }
}
