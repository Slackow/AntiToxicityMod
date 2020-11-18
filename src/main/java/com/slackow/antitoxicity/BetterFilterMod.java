package com.slackow.antitoxicity;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.ClickEvent;
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
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.*;
import java.util.function.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static net.minecraft.event.ClickEvent.Action.RUN_COMMAND;
import static net.minecraft.event.ClickEvent.Action.SUGGEST_COMMAND;
import static net.minecraft.event.HoverEvent.Action.SHOW_TEXT;
import static net.minecraft.util.EnumChatFormatting.*;

@Mod(modid = BetterFilterMod.MODID, version = BetterFilterMod.VERSION, clientSideOnly = true)
public class BetterFilterMod {
    public static final String MODID = "BetterFilterMod";
    public static final String VERSION = "1.1";

    private static final Path dataDirLocation = Paths.get("betterfilter");
    public static final String SUFFIX = "-list.txt";

    private final List<MessageBlockRule> messageBlockRules = new ArrayList<>();
    private final List<MessageBlockRule> exceptionRules = new ArrayList<>();
    private static final Map<String, String> listMap = new HashMap<>();
    private boolean isEnabled = true;

    private ClientChatReceivedEvent lastBlockedEvent;

    private String cut(String str) {
        return str.substring(0, str.length() - SUFFIX.length());
    }

    private void load() {
        Path dataPath = dataDirLocation.resolve("data.json");
        if (Files.isDirectory(dataDirLocation)) {
            listMap.clear();
            try {
                Files.list(dataDirLocation)
                        .filter(path -> path.toString().endsWith(SUFFIX)).forEach(path -> {
                    try {
                        listMap.put(cut(path.getFileName().toString()), "(?:" + Files.lines(path).collect(Collectors.joining("|")) + ")");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }


        if (Files.exists(dataPath)) {
            JsonParser parser = new JsonParser();

            JsonElement parse;
            try {
                parse = parser.parse(Files.lines(dataPath).collect(Collectors.joining("\n")));
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
                return "/betterfilter add {id} {regex} [msg]\n" +
                        "/betterfilter addexception {id} {regex}\n" +
                        "/betterfilter move {id} [up|down|position]\n" +
                        "/betterfilter editmsg {id} [msg]\n" +
                        "/betterfilter edit {id} {regex}\n" +
                        "/betterfilter remove {id}\n" +
                        "/betterfilter list\n" +
                        "/betterfilter toggle\n" +
                        "/betterfilter reload\n" +
                        "/betterfilter log\n" +
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
                final IChatComponent feedback;
                if (args.length <= 0) {
                    feedback = new ChatComponentText(getCommandUsage(sender)).setChatStyle(new ChatStyle().setColor(RED));
                } else {
                    main:
                    switch (args[0]) {
                        case "addexception":
                        case "add": {
                            String str = Arrays.stream(args).skip(2).collect(Collectors.joining(" "));
                            if (!str.isEmpty()) {
                                String id = args[1];
                                if (id.startsWith("/") || id.startsWith("\"")) {
                                    feedback = new ChatComponentText(RED + "You need an ID");
                                    break;
                                } else if (getRulesAndExceptions().anyMatch(rule -> id.equals(rule.id))) {
                                    feedback = new ChatComponentText(RED + "'" + id + "' already exists");
                                    break;
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
                                        feedback = new ChatComponentText(RED + "Invalid Regex");
                                        break;
                                    }
                                    ("add".equals(args[0]) ? messageBlockRules : exceptionRules).add(messageBlockRule);
                                    feedback = new ChatComponentText(GREEN + "Added '" + id + "'");
                                    save();
                                } else {
                                    feedback = new ChatComponentText(RED + "Invalid Regex/Message");
                                }
                            } else {
                                feedback = new ChatComponentText(RED + "Insufficient number of args, regex missing");
                            }
                            break;
                        }
                        case "edit": {
                            if (args.length >= 2) {
                                String id = args[1];

                                Optional<MessageBlockRule> rule = getRulesAndExceptions().filter(aRule -> aRule.id.equals(id)).findAny();

                                if (!rule.isPresent()) {
                                    feedback = new ChatComponentText(RED + "Did not find '" + args[1] + "'");
                                    break;
                                }

                                String regex = Arrays.stream(args).skip(2).collect(Collectors.joining(" "));

                                boolean isLiteral = regex.startsWith("\"");
                                Matcher matcher = (isLiteral ? literalPattern : regexPattern).matcher(regex);
                                if (matcher.matches()) {
                                    String pattern = matcher.group(1);

                                    if (isLiteral) {
                                        pattern = pattern.replace("\\\"", "\"");
                                    }
                                    boolean isCaseInsensitive = "i".equals(matcher.group(2));

                                    try {
                                        rule.get().setPattern(pattern, isCaseInsensitive, isLiteral);
                                    } catch (PatternSyntaxException e) {
                                        feedback = new ChatComponentText(RED + "Invalid Regex");
                                        break;
                                    }


                                    save();
                                    feedback = new ChatComponentText(GREEN + "Successfully edited '" + id + "'");
                                } else {
                                    feedback = new ChatComponentText(RED + "Invalid Regex");
                                }

                            } else {
                                feedback = new ChatComponentText(RED + "No ID provided");
                            }
                            break;
                        }
                        case "editmsg":
                            if (args.length >= 2) {
                                String msg = translateColorCodes(Arrays.stream(args).skip(2).collect(Collectors.joining(" ")));
                                Optional<MessageBlockRule> messageRule = getRulesAndExceptions()
                                        .filter(rule -> rule.id.equals(args[1]))
                                        .findAny();
                                if (messageRule.isPresent()) {
                                    messageRule.get().message = msg;
                                    save();
                                    feedback = new ChatComponentText(msg.isEmpty() ? "Removed message from " + messageRule.get().id : "Updated message to \"" + msg + "\"");
                                } else {
                                    feedback = new ChatComponentText(RED + "Did not find '" + args[1] + "'");
                                }
                            } else {
                                feedback = new ChatComponentText(RED + "No ID provided");
                            }
                            break;
                        case "remove":
                            if (args.length >= 2) {
                                Predicate<MessageBlockRule> predicate = messageBlockRule -> messageBlockRule.id.equals(args[1]);

                                if (messageBlockRules.removeIf(predicate) || exceptionRules.removeIf(predicate)) {
                                    save();
                                    feedback = new ChatComponentText(GREEN + "Removed '" + args[1] + "'");
                                } else {
                                    feedback = new ChatComponentText(RED + "Did not find '" + args[1] + "'");
                                }
                            } else {
                                feedback = new ChatComponentText(RED + "No ID provided");
                            }
                            break;
                        case "move":
                            if (args.length >= 2) {
                                if (args.length >= 3) {
                                    int index = -1;
                                    boolean isException = false;
                                    for (int i = 0; i < messageBlockRules.size(); i++) {
                                        if (messageBlockRules.get(i).id.equals(args[1])) {
                                            index = i;
                                            break;
                                        }
                                    }
                                    if (index < 0) {
                                        isException = true;
                                        for (int i = 0; i < exceptionRules.size(); i++) {
                                            if (exceptionRules.get(i).id.equals(args[1])) {
                                                index = i;
                                                break;
                                            }
                                        }
                                    }
                                    if (index >= 0) {
                                        List<MessageBlockRule> rules = isException ? exceptionRules : messageBlockRules;
                                        int otherIndex;
                                        switch (args[2]) {
                                            case "up":
                                                otherIndex = (index == 0 ? rules.size() : index) - 1;
                                                break;
                                            case "down":
                                                otherIndex = (index + 1) % rules.size();
                                                break;
                                            case "top":
                                                otherIndex = 0;
                                                break;
                                            case "bottom":
                                                otherIndex = rules.size() - 1;
                                                break;
                                            default:
                                                if (args[2].matches("[1-9]\\d*") && (otherIndex = Integer.parseInt(args[2])) <= rules.size()) {
                                                    otherIndex--;
                                                } else {
                                                    feedback = new ChatComponentText(RED + "Invalid Index/Motion please use up, down, top, bottom, or specify a number from 1-" + rules.size());
                                                    break main;
                                                }
                                                break;
                                        }
                                        rules.add(otherIndex, rules.remove(index));
                                        save();
                                        feedback = new ChatComponentText(GREEN + "Succeeded in moving '" + args[1] + "' from position " + (index + 1) + " to " + (otherIndex + 1));
                                    } else {
                                        feedback = new ChatComponentText(RED + "Did not find '" + args[1] + "'");
                                    }

                                } else {
                                    feedback = new ChatComponentText(RED + "No Position provided");
                                }
                            } else {
                                feedback = new ChatComponentText(RED + "No ID provided");
                            }
                            break;
                        case "list":
                            if (getRulesAndExceptions().count() == 0L) {
                                feedback = new ChatComponentText(RED + BOLD.toString() + "There are no rules");
                            } else {
                                feedback = new ChatComponentText("");

                                ChatStyle chatStyle = new ChatStyle().setColor(GREEN).setBold(true);
                                if (!messageBlockRules.isEmpty()) {
                                    feedback.appendSibling(new ChatComponentText("Rules: ")
                                            .setChatStyle(chatStyle));
                                    messageBlockRules.stream().map(rule -> {
                                        IChatComponent component = new ChatComponentText("");
                                        component.appendSibling(new ChatComponentText("\n - ")
                                                .setChatStyle(new ChatStyle()
                                                        .setChatHoverEvent(new HoverEvent(SHOW_TEXT, new ChatComponentText("Click to adjust priority")))
                                                        .setChatClickEvent(new ClickEvent(SUGGEST_COMMAND, "/betterfilter move " + rule.id + " "))));
                                        component.appendSibling(new ChatComponentText(rule.id + ": ")
                                                .setChatStyle(new ChatStyle().setColor(DARK_GREEN)));

                                        component.appendSibling(new ChatComponentText("[REGEX]")
                                                .setChatStyle(new ChatStyle().setColor(DARK_AQUA)
                                                        .setChatHoverEvent(new HoverEvent(SHOW_TEXT, new ChatComponentText(rule.toString())))
                                                        .setChatClickEvent(new ClickEvent(SUGGEST_COMMAND, "/betterfilter edit " + rule.id + " " + rule.toString()))));
                                        if (!StringUtils.isNullOrEmpty(rule.message))
                                            component.appendSibling(new ChatComponentText(" [MSG]")
                                                    .setChatStyle(new ChatStyle().setColor(DARK_GRAY)
                                                            .setChatHoverEvent(new HoverEvent(SHOW_TEXT, new ChatComponentText(GREEN + rule.message)))
                                                            .setChatClickEvent(new ClickEvent(SUGGEST_COMMAND, "/betterfilter editmsg " + rule.id + " " + rule.message.replace('\u00A7', '&')))));
                                        return component;
                                    }).forEachOrdered(feedback::appendSibling);
                                }
                                if (!exceptionRules.isEmpty()) {
                                    feedback.appendSibling(new ChatComponentText("\nExceptions: ")
                                            .setChatStyle(chatStyle));
                                    exceptionRules.stream().map(rule -> {
                                        IChatComponent component = new ChatComponentText("");
                                        component.appendSibling(new ChatComponentText("\n - ")
                                                .setChatStyle(new ChatStyle()
                                                        .setChatHoverEvent(new HoverEvent(SHOW_TEXT, new ChatComponentText("Click to adjust priority")))
                                                        .setChatClickEvent(new ClickEvent(SUGGEST_COMMAND, "/betterfilter move " + rule.id + " "))));
                                        component.appendSibling(new ChatComponentText(rule.id + ": ")
                                                .setChatStyle(new ChatStyle().setColor(DARK_GREEN).setChatHoverEvent(null).setChatClickEvent(null)));
                                        component.appendSibling(new ChatComponentText("[REGEX]")
                                                .setChatStyle(new ChatStyle().setColor(DARK_AQUA)
                                                        .setChatHoverEvent(new HoverEvent(SHOW_TEXT, new ChatComponentText(rule.toString())))
                                                        .setChatClickEvent(new ClickEvent(SUGGEST_COMMAND, "/betterfilter edit " + rule.id + " " + rule.toString()))));
                                        return component;
                                    }).forEachOrdered(feedback::appendSibling);
                                }
                            }
                            break;
                        case "toggle":
                            isEnabled = !isEnabled;
                            save();
                            feedback = new ChatComponentText(isEnabled ? GREEN + "Enabled Filter" : RED + "Disabled Filter");
                            break;
                        case "log":
                            if (lastBlockedEvent != null) {
                                try {
                                    Path path = dataDirLocation.resolve("log.txt");
                                    Files.write(path,
                                            Arrays.asList(LocalDateTime.now().toString(),
                                                    removeColorCodes(lastBlockedEvent.message.getUnformattedText())),
                                            Files.exists(path) ? APPEND : CREATE);
                                } catch (IOException e) {
                                    feedback = new ChatComponentText(RED + "Could not log, file writing failed");
                                    break;
                                }
                                feedback = new ChatComponentText(GREEN + "Logged");
                            } else {
                                feedback = new ChatComponentText(RED + "Haven't blocked a message to log");
                            }
                            break;
                        case "reload":
                            load();
                            feedback = new ChatComponentText(GREEN + "Reloaded rules from file");
                            break;
                        case "dir":
                            try {
                                Desktop.getDesktop().open(dataDirLocation.toFile());
                            } catch (IOException e) {
                                feedback = new ChatComponentText(RED + "Problem with opening the dir");
                                break;
                            }
                            feedback = new ChatComponentText(GREEN + "Opened Directory");
                            break;
                        default:
                            feedback = new ChatComponentText(RED + "Unrecognized SubCommand");
                            break;
                    }
                }
                if (feedback.getUnformattedText().startsWith(GREEN.toString())) {
                    feedback.setChatStyle(new ChatStyle()
                            .setChatHoverEvent(new HoverEvent(SHOW_TEXT, new ChatComponentText("Click to List All Rules")))
                            .setChatClickEvent(new ClickEvent(RUN_COMMAND, "/bfilter list")));
                }
                sender.addChatMessage(feedback);
            }

            @Override
            public boolean canCommandSenderUseCommand(ICommandSender sender) {
                return true;
            }

            @Override
            public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
                if (args.length == 1) {
                    return Stream.of("add", "addexception", "remove", "edit", "editmsg", "toggle", "list", "move", "log", "reload", "dir")
                            .filter(suggestion -> suggestion.startsWith(args[0].toLowerCase()))
                            .collect(Collectors.toList());
                } else if (args.length == 2) {
                    switch (args[0]) {
                        case "remove":
                        case "editmsg":
                        case "edit":
                        case "move":
                            return getRulesAndExceptions()
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

    private Stream<MessageBlockRule> getRulesAndExceptions() {
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

        File file = dataDirLocation.toFile();
        if (!file.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            file.mkdirs();
        }

        try (Writer writer = Files.newBufferedWriter(dataDirLocation.resolve("data.json"))) {
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
                    lastBlockedEvent = e;
                    e.setCanceled(true);
                    if (!StringUtils.isNullOrEmpty(messageBlockRule.message)) {
                        IChatComponent response = new ChatComponentText(GREEN + messageBlockRule.message);
                        response.getChatStyle().setChatHoverEvent(new HoverEvent(SHOW_TEXT,
                                new ChatComponentText(WordUtils.wrap(e.message.getFormattedText(), 70, "\n", false))));
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
    private static final Pattern getFormatting = Pattern.compile("\\u00A7[\\da-fk-or]");

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
            this.id = id;
            if (message != null)
                this.message = translateColorCodes(message);
            setPattern(patternLiteral, isCaseInsensitive, isLiteral);
        }

        public void setPattern(String patternLiteral, boolean isCaseInsensitive, boolean isLiteral) throws PatternSyntaxException {
            if (isLiteral) {
                patternLiteral = Pattern.quote(patternLiteral);
            }


            for (Map.Entry<String, String> entry : listMap.entrySet()) {
                patternLiteral = patternLiteral.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            pattern = isCaseInsensitive ?
                    Pattern.compile(patternLiteral, Pattern.CASE_INSENSITIVE) :
                    Pattern.compile(patternLiteral);

            this.patternLiteral = patternLiteral;
            this.isCaseInsensitive = isCaseInsensitive;
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
            return "/" + patternLiteral + (isCaseInsensitive ? "/i" : "/");
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
     *
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
