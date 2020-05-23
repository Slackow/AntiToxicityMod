package com.slackow.antitoxicity;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.*;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.commons.lang3.text.WordUtils;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static net.minecraft.util.EnumChatFormatting.*;

@Mod(modid = AntiToxicityMod.MODID, version = AntiToxicityMod.VERSION, clientSideOnly = true)
public class AntiToxicityMod {
    public static final String MODID = "AntiToxicityMod";
    public static final String VERSION = "1.0";

    private static final String dataLocation = "antitoxicity/data.json";

    private final List<MessageBlockRule> messageBlockRules = new ArrayList<>();
    private final List<MessageBlockRule> exceptionRules = new ArrayList<>();
    private boolean isEnabled = true;

    private ClientChatReceivedEvent lastEvent;

    private void load() {
        Path path = Paths.get(dataLocation);
        if (Files.exists(path)) {
            JsonParser parser = new JsonParser();

            JsonElement parse;
            try {
                parse = parser.parse(Files.lines(path).collect(Collectors.joining("\n")));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            JsonObject data = parse.getAsJsonObject();
            messageBlockRules.clear();
            exceptionRules.clear();
            if (data.has("rules"))
                for (JsonElement rule : data.getAsJsonArray("rules")) {
                    messageBlockRules.add(MessageBlockRule.fromJsonObject(rule.getAsJsonObject()));
                }
            if (data.has("exceptions"))
                for (JsonElement exception : data.getAsJsonArray("exceptions")) {
                    exceptionRules.add(MessageBlockRule.fromJsonObject(exception.getAsJsonObject()));
                }
            if (data.has("toggled"))
                isEnabled = data.get("toggled").getAsBoolean();
        } else {
            // add default filters and save them.
            messageBlockRules.add(new MessageBlockRule("BlockL",
                    "(?<!the |=)\\bL\\b",
                    "Blocked Toxicity",
                    true,
                    false));
            messageBlockRules.add(new MessageBlockRule("BlockPartyAds",
                    "\\/p(?:arty)? join \\w{1,16}",
                    "Blocked Party Ad",
                    true,
                    false));
            messageBlockRules.add(new MessageBlockRule("BlockGuildAds",
                    "\\/g(?:uild)? join\\b",
                    "Blocked Guild Ad",
                    true,
                    false));
            exceptionRules.add(new MessageBlockRule("UsernameOverride",
                    Minecraft.getMinecraft().getSession().getUsername(),
                    "",
                    true,
                    true));
            save();
        }
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        load();
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new ICommand() {
            @Override
            public String getCommandName() {
                return "antitoxicity";
            }

            @Override
            public String getCommandUsage(ICommandSender sender) {
                return "/betterfilter add {id} {regex} [message] | " +
                        "/betterfilter addexception {id} {regex} | " +
                        "/betterfilter editmsg {id} [msg] | " +
                        "/betterfilter remove {id} | " +
                        "/betterfilter list | " +
                        "/betterfilter toggle | " +
                        "/betterfilter reload | " +
                        "/betterfilter log | " +
                        "/betterfilter dir";
            }

            @Override
            public List<String> getCommandAliases() {
                return Arrays.asList("antitox", "betterfilter", "bfilter");
            }

            private final Pattern regexPattern = Pattern.compile("/((?:[^/]|\\\\/)+)/(i?)(?: (.+))?");
            private final Pattern literalPattern = Pattern.compile("\"((?:[^\"]|\\\\\")+)\"(i?)(?: (.+))?");



            @Override
            public void processCommand(ICommandSender sender, String[] args) {
                if (args.length <= 0) {
                    for (String commandUsage : getCommandUsage(sender).split("\\s*\\|\\s*")) {
                        sender.addChatMessage(new ChatComponentText(RED + commandUsage));
                    }
                    return;
                }
                switch (args[0]) {
                    case "addexception":
                    case "add":
                        String str = Arrays.stream(args).skip(2).collect(Collectors.joining(" "));
                        if (!str.isEmpty()) {
                            String id = args[1];
                            if (id.startsWith("/") || id.startsWith("\"")) {
                                sender.addChatMessage(new ChatComponentText(RED + "You need an ID"));
                                return;
                            }
                            if (getRules()
                                    .anyMatch(rule -> id.equals(rule.id))) {
                                sender.addChatMessage(new ChatComponentText(RED + "'" + id + "' already exists"));
                                return;
                            }


                            boolean isLiteral = str.startsWith("\"");
                            Matcher matcher = (isLiteral ? literalPattern : regexPattern).matcher(str);
                            if (matcher.matches()) {
                                String pattern = matcher.group(1);

                                if (isLiteral) {
                                    pattern = pattern.replace("\\\"", "\"");
                                }

                                boolean isCaseInsensitive = "i".equals(matcher.group(2));
                                String message = matcher.group(3);
                                if (message == null) {
                                    message = "";
                                }

                                MessageBlockRule messageBlockRule;
                                try {
                                    messageBlockRule = new MessageBlockRule(id, pattern, message, isCaseInsensitive, isLiteral);
                                } catch (PatternSyntaxException e) {
                                    sender.addChatMessage(new ChatComponentText(RED + "Invalid Regex/Message"));
                                    return;
                                }
                                ("add".equals(args[0]) ? messageBlockRules : exceptionRules).add(messageBlockRule);
                                sender.addChatMessage(new ChatComponentText(GREEN + "Added '" + id + "'"));
                                save();
                            } else {
                                sender.addChatMessage(new ChatComponentText(RED + "Invalid Regex/Message"));
                            }
                        } else {
                            sender.addChatMessage(new ChatComponentText(RED + "Insufficient number of args, regex missing"));
                        }
                        break;
                    case "editmsg":
                        if (args.length >= 2) {
                            String msg = translateColorCodes(Arrays.stream(args).skip(2).collect(Collectors.joining(" ")));
                            long num = getRules()
                                    .filter(rule -> rule.id.equals(args[1]))
                                    .peek(rule -> {
                                        rule.message = msg;
                                        sender.addChatMessage(new ChatComponentText(msg.isEmpty() ? "Removed message from " + rule.id : "Updated message to \"" + msg + "\""));
                                        save();
                                    }).count();
                            if (num <= 0) {
                                sender.addChatMessage(new ChatComponentText(RED + "Did not find '" + args[1] + "'"));
                            }
                        } else {
                            sender.addChatMessage(new ChatComponentText(RED + "No ID provided"));
                        }
                        break;
                    case "remove":
                        if (args.length >= 2) {
                            Predicate<MessageBlockRule> predicate = messageBlockRule -> messageBlockRule.id.equals(args[1]);

                            if (messageBlockRules.removeIf(predicate) || exceptionRules.removeIf(predicate)) {
                                save();
                                sender.addChatMessage(new ChatComponentText(GREEN + "Removed '" + args[1] + "'"));
                            } else {
                                sender.addChatMessage(new ChatComponentText(RED + "Did not find '" + args[1] + "'"));
                            }
                        } else {
                            sender.addChatMessage(new ChatComponentText(RED + "No ID provided"));
                        }
                        break;
                    case "list":
                        if (messageBlockRules.isEmpty() && exceptionRules.isEmpty()) {
                            sender.addChatMessage(new ChatComponentText(RED + BOLD.toString() + "There are no rules"));
                        } else {
                            if (!messageBlockRules.isEmpty()) {
                                sender.addChatMessage(new ChatComponentText("Rules: ")
                                        .setChatStyle(new ChatStyle().setColor(GREEN).setBold(true)));
                                messageBlockRules.stream().map(String::valueOf).map(ChatComponentText::new)
                                        .forEachOrdered(sender::addChatMessage);
                            }
                            if (!exceptionRules.isEmpty()) {
                                sender.addChatMessage(new ChatComponentText("\nExceptions: ")
                                        .setChatStyle(new ChatStyle().setColor(GREEN).setBold(true)));
                                exceptionRules.stream().map(String::valueOf).map(ChatComponentText::new)
                                        .forEachOrdered(sender::addChatMessage);
                            }
                        }
                        break;
                    case "toggle":
                        isEnabled = !isEnabled;
                        save();
                        sender.addChatMessage(new ChatComponentText(isEnabled ? GREEN + "Enabled Filter" : RED + "Disabled Filter"));
                        break;
                    case "log":
                        if (lastEvent != null) {
                            try {
                                Path path = Paths.get(dataLocation).resolveSibling("log.txt");
                                Files.write(path,
                                        Arrays.asList(LocalDateTime.now().toString(),
                                                removeColorCodes(lastEvent.message.getUnformattedText())),
                                        Files.exists(path) ? APPEND : CREATE);
                                sender.addChatMessage(new ChatComponentText(GREEN + "Logged"));
                            } catch (IOException e) {
                                sender.addChatMessage(new ChatComponentText(RED + "Could not log, file writing failed"));
                            }
                        } else {
                            sender.addChatMessage(new ChatComponentText(RED + "Haven't blocked a message to log"));
                        }
                        break;
                    case "reload":
                        load();
                        sender.addChatMessage(new ChatComponentText(GREEN + "Reloaded rules from file"));
                        break;
                    case "dir":
                        try {
                            Desktop.getDesktop().open(new File(dataLocation).getParentFile());
                            sender.addChatMessage(new ChatComponentText(GREEN + "Opened Directory"));
                        } catch (IOException e) {
                            sender.addChatMessage(new ChatComponentText(RED + "Problem with saving"));
                        }
                        break;
                    default:
                        sender.addChatMessage(new ChatComponentText(RED + "Unrecognized SubCommand"));
                        break;
                }
            }

            @Override
            public boolean canCommandSenderUseCommand(ICommandSender sender) {
                return true;
            }

            @Override
            public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
                if (args.length == 1) {
                    return Stream.of("add", "addexception", "remove", "editmsg", "toggle", "list", "log", "reload", "dir")
                            .filter(suggestion -> suggestion.startsWith(args[0].toLowerCase()))
                            .collect(Collectors.toList());
                } else if (args.length == 2) {
                    switch (args[0]) {
                        case "remove":
                        case "editmsg":
                            return getRules()
                                    .map(rule -> rule.id)
                                    .filter(suggestion -> suggestion.toLowerCase().startsWith(args[1].toLowerCase()))
                                    .collect(Collectors.toList());
                    }
                }
                return null;
            }

            @Override
            public boolean isUsernameIndex(String[] args, int index) {
                return false;
            }

            @SuppressWarnings("NullableProblems")
            @Override
            public int compareTo(ICommand o) {
                return 0;
            }
        });
    }

    private Stream<MessageBlockRule> getRules() {
        return Stream.concat(messageBlockRules.stream(), exceptionRules.stream());
    }

    public void save() {
        JsonObject json = new JsonObject();

        JsonArray rules = messageBlockRules.stream()
                .map(MessageBlockRule::toJsonObject)
                .collect(toJsonArray);
        json.add("rules", rules);

        JsonArray exceptions = exceptionRules.stream()
                .map(MessageBlockRule::toJsonObject)
                .collect(toJsonArray);
        json.add("exceptions", exceptions);

        json.addProperty("toggled", isEnabled);

        File file = new File(dataLocation);
        if (!file.getParentFile().isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
        }

        try (Writer writer = new FileWriter(file)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(json, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Collector<JsonElement, JsonArray, JsonArray> toJsonArray = new Collector<JsonElement, JsonArray, JsonArray>() {
        @Override
        public Supplier<JsonArray> supplier() {
            return JsonArray::new;
        }

        @Override
        public BiConsumer<JsonArray, JsonElement> accumulator() {
            return JsonArray::add;
        }

        @Override
        public BinaryOperator<JsonArray> combiner() {
            return (left, right) -> {
                left.addAll(right);
                return left;
            };
        }

        @Override
        public Function<JsonArray, JsonArray> finisher() {
            return Function.identity();
        }

        @Override
        public Set<Characteristics> characteristics() {
            return Collections.singleton(Characteristics.IDENTITY_FINISH);
        }
    };

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent e) {
        if (isEnabled) {
            // apparently getUnformattedText doesn't remove all color codes so I have to do it MYSELF
            String text = removeColorCodes(e.message.getUnformattedText());
            for (MessageBlockRule exceptionRule : exceptionRules) {
                if (exceptionRule.pattern.matcher(text).find()) {
                    return;
                }
            }


            for (MessageBlockRule messageBlockRule : messageBlockRules) {
                Matcher matcher = messageBlockRule.pattern.matcher(text);
                if (matcher.find()) {
                    //chat message was stopped, so make sure it can be logged
                    lastEvent = e;
                    e.setCanceled(true);
                    if (!StringUtils.isNullOrEmpty(messageBlockRule.message)) {
                        IChatComponent response = new ChatComponentText(GREEN + messageBlockRule.message);
                        IChatComponent message = e.message;
                        response.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(WordUtils.wrap(message.getFormattedText(), 70, "\n", false))));
                        Minecraft.getMinecraft().thePlayer.addChatMessage(response);
                    }
                    break;
                }
            }
        }
    }

    /**
     * getFormatting is a pattern that finds any sequence of § followed by a valid character that would be
     * interpreted by minecraft as a color or formatting change.
     */
    private static final Pattern getFormatting = Pattern.compile("\\u00A7[\\da-fk-nr]");

    /**
     * @param text the text to remove codes from
     * @return the same input, except all the color codes removed
     */
    private static String removeColorCodes(String text) {
        return getFormatting.matcher(text).replaceAll("");
    }

    static class MessageBlockRule {
        String id;
        Pattern pattern;
        String patternLiteral;
        String message;
        boolean isCaseInsensitive;


        MessageBlockRule(String id, String patternLiteral, String message, boolean isCaseInsensitive, boolean isLiteral) throws PatternSyntaxException {
            if (isLiteral) {
                patternLiteral = Pattern.quote(patternLiteral);
            }
            this.id = id;
            this.patternLiteral = patternLiteral;
            this.isCaseInsensitive = isCaseInsensitive;
            if (message != null)
                this.message = translateColorCodes(message);
            pattern = isCaseInsensitive ?
                    Pattern.compile(patternLiteral, Pattern.CASE_INSENSITIVE) :
                    Pattern.compile(patternLiteral);
        }

        JsonObject toJsonObject() {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("id", id);
            jsonObject.addProperty("patternLiteral", patternLiteral);
            jsonObject.addProperty("message", message);
            jsonObject.addProperty("isCaseInsensitive", isCaseInsensitive);
            return jsonObject;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(id);
            sb.append(": /");
            sb.append(pattern);
            sb.append(isCaseInsensitive ? "/i" : "/");
            if (!StringUtils.isNullOrEmpty(message)) {
                sb.append(" - \"");
                sb.append(message);
                sb.append('"');
            }
            return sb.toString();
        }

        public static MessageBlockRule fromJsonObject(JsonObject jsonObject) {
            if (Stream.of("patternLiteral", "isCaseInsensitive", "message", "id").allMatch(jsonObject::has)) {
                return new MessageBlockRule(jsonObject.get("id").getAsString(), jsonObject.get("patternLiteral").getAsString(),
                        jsonObject.get("message").getAsString(),
                        jsonObject.get("isCaseInsensitive").getAsBoolean(),
                        false);
            }
            return null;
        }

    }


    private static final EnumChatFormatting[] COLORS = EnumChatFormatting.values();

    /**
     * replaces all instances of an & with a § when it will create a color code
     * for example "&4" changes to "§4" it also replaces things like "&lt;red>" with "§4"
     * this is so the user can input colors and formatting into the feedback message
     * @param message a message to translate
     * @return the translated message
     */
    private static String translateColorCodes(String message) {
        if (message == null) return "";

        for (EnumChatFormatting color : COLORS) {
            message = message.replace("<" + color.getFriendlyName() + ">", color.toString());
        }

        return message.replaceAll("&([\\da-fk-or])", "\u00a7$1");
    }
}
