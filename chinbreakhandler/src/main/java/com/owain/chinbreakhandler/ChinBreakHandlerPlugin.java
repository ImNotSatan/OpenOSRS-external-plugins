package com.owain.chinbreakhandler;

import com.owain.chinbreakhandler.ui.ChinBreakHandlerPanel;
import com.owain.chinbreakhandler.ui.utils.IntRandomNumberGenerator;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuOpcode;
import net.runelite.api.Point;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "Chin break handler",
	description = "Automatically takes breaks for you (?)",
	type = PluginType.MISCELLANEOUS
)
@Slf4j
public class ChinBreakHandlerPlugin extends Plugin
{
	public final static String CONFIG_GROUP = "chinbreakhandler";

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClientThread clientThread;

	@Inject
	@Getter
	private ConfigManager configManager;

	@Inject
	private ChinBreakHandler chinBreakHandler;

	public static String data;

	private NavigationButton navButton;
	private ChinBreakHandlerPanel panel;

	public final Map<Plugin, Disposable> secondDisposable = new HashMap<>();
	public Disposable activeBreaks;
	public Disposable secondsDisposable;
	public Disposable activeDisposable;
	public Disposable logoutDisposable;

	private ChinBreakHandlerState state = ChinBreakHandlerState.NULL;
	private ExecutorService executorService;

	protected void startUp()
	{
		executorService = Executors.newSingleThreadExecutor();

		panel = injector.getInstance(ChinBreakHandlerPanel.class);

		final BufferedImage icon = ImageUtil.getResourceStreamFromClass(getClass(), "chin_special.png");

		navButton = NavigationButton.builder()
			.tooltip("Chin break handler")
			.icon(icon)
			.priority(4)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		activeBreaks = chinBreakHandler
			.getCurrentActiveBreaksObservable()
			.subscribe(this::breakActivated);

		secondsDisposable = Observable
			.interval(1, TimeUnit.SECONDS)
			.subscribe(this::seconds);

		activeDisposable = chinBreakHandler
			.getActiveObservable()
			.subscribe(
				(ignore) ->
				{
					if (!ignore.isEmpty() && data == null)
					{
						if (!navButton.isSelected())
						{
							navButton.getOnSelect().run();
						}
					}
				}
			);

		logoutDisposable = chinBreakHandler
			.getlogoutActionObservable()
			.subscribe(
				(plugin) ->
				{
					if (plugin != null)
					{
						state = ChinBreakHandlerState.LOGOUT;
					}
				}
			);
	}

	protected void shutDown()
	{
		executorService.shutdown();

		clientToolbar.removeNavigation(navButton);

		panel.pluginDisposable.dispose();
		panel.activeDisposable.dispose();
		panel.currentDisposable.dispose();

		for (Disposable disposable : secondDisposable.values())
		{
			if (!disposable.isDisposed())
			{
				disposable.dispose();
			}
		}

		if (activeBreaks != null && !activeBreaks.isDisposed())
		{
			activeBreaks.dispose();
		}

		if (secondsDisposable != null && !secondsDisposable.isDisposed())
		{
			secondsDisposable.dispose();
		}

		if (activeDisposable != null && !activeDisposable.isDisposed())
		{
			activeDisposable.dispose();
		}

		if (logoutDisposable != null && !logoutDisposable.isDisposed())
		{
			logoutDisposable.dispose();
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		chinBreakHandler.configChanged.onNext(configChanged);
	}

	public void scheduleBreak(Plugin plugin)
	{
		int from = Integer.parseInt(configManager.getConfiguration(ChinBreakHandlerPlugin.CONFIG_GROUP, sanitizedName(plugin) + "-thresholdfrom"));
		int to = Integer.parseInt(configManager.getConfiguration(ChinBreakHandlerPlugin.CONFIG_GROUP, sanitizedName(plugin) + "-thresholdto"));

		int random = new IntRandomNumberGenerator(from, to).nextInt();

		chinBreakHandler.planBreak(plugin, Instant.now().plus(random, ChronoUnit.MINUTES));
	}

	private void breakActivated(Pair<Plugin, Instant> pluginInstantPair)
	{
		Plugin plugin = pluginInstantPair.getKey();

		if (!chinBreakHandler.getPlugins().get(plugin) || Boolean.parseBoolean(configManager.getConfiguration(ChinBreakHandlerPlugin.CONFIG_GROUP, sanitizedName(plugin) + "-logout")))
		{
			state = ChinBreakHandlerState.LOGOUT;
		}
	}

	private void seconds(long ignored)
	{
		Map<Plugin, Instant> activeBreaks = chinBreakHandler.getActiveBreaks();

		if (activeBreaks.isEmpty() || client.getGameState() != GameState.LOGIN_SCREEN)
		{
			return;
		}

		boolean finished = true;

		for (Instant duration : activeBreaks.values())
		{
			if (Instant.now().isBefore(duration))
			{
				finished = false;
			}
		}

		if (finished)
		{
			boolean manual = Boolean.parseBoolean(configManager.getConfiguration(ChinBreakHandlerPlugin.CONFIG_GROUP, "accountselection"));

			String username = null;
			String password = null;

			if (manual)
			{
				username = configManager.getConfiguration(ChinBreakHandlerPlugin.CONFIG_GROUP, "accountselection-manual-username");
				password = configManager.getConfiguration(ChinBreakHandlerPlugin.CONFIG_GROUP, "accountselection-manual-password");
			}
			else
			{
				String account = configManager.getConfiguration(ChinBreakHandlerPlugin.CONFIG_GROUP, "accountselection-profiles-account");

				if (data == null)
				{
					return;
				}

				Optional<String> accountData = Arrays.stream(data.split("\\n"))
					.filter(s -> s.startsWith(account))
					.findFirst();

				if (accountData.isPresent())
				{
					String[] parts = accountData.get().split(":");
					username = parts[1];
					if (parts.length == 3)
					{
						password = parts[2];
					}
				}
			}

			if (username != null && password != null)
			{
				String finalUsername = username;
				String finalPassword = password;

				clientThread.invoke(() ->
					{
						client.setUsername(finalUsername);
						client.setPassword(finalPassword);

						client.setGameState(GameState.LOGGING_IN);
					}
				);

			}
		}
	}

	public static String sanitizedName(Plugin plugin)
	{
		return plugin.getName().toLowerCase().replace(" ", "");
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
		{
			state = ChinBreakHandlerState.LOGIN_SCREEN;
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (state == ChinBreakHandlerState.LOGIN_SCREEN && !chinBreakHandler.getActiveBreaks().isEmpty())
		{
			handleLoginScreen();
		}
		else if (state == ChinBreakHandlerState.LOGOUT)
		{
			sendKey(KeyEvent.VK_ESCAPE);

			state = ChinBreakHandlerState.LOGOUT_TAB;
		}
		else if (state == ChinBreakHandlerState.LOGOUT_TAB)
		{
			// Logout tab
			client.runScript(915, 10);
			state = ChinBreakHandlerState.LOGOUT_BUTTON;
		}
		else if (state == ChinBreakHandlerState.LOGOUT_BUTTON)
		{
			leftClickRandom();
		}
		else if (state == ChinBreakHandlerState.INVENTORY)
		{
			// Inventory
			client.runScript(915, 3);
			state = ChinBreakHandlerState.NULL;
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked menuOptionClicked)
	{
		if (state == ChinBreakHandlerState.LOGIN_MESSAGE_SCREEN)
		{
			menuOptionClicked.setOption("Play");
			menuOptionClicked.setTarget("");
			menuOptionClicked.setIdentifier(1);
			menuOptionClicked.setOpcode(MenuOpcode.CC_OP.getId());
			menuOptionClicked.setParam0(-1);
			menuOptionClicked.setParam1(24772686);

			state = ChinBreakHandlerState.INVENTORY;

			for (Plugin plugin : chinBreakHandler.getActiveBreaks().keySet())
			{
				chinBreakHandler.stopBreak(plugin);
			}
		}
		else if (state == ChinBreakHandlerState.LOGOUT_BUTTON)
		{
			menuOptionClicked.setOption("Logout");
			menuOptionClicked.setTarget("");
			menuOptionClicked.setIdentifier(1);
			menuOptionClicked.setOpcode(MenuOpcode.CC_OP.getId());
			menuOptionClicked.setParam0(-1);
			menuOptionClicked.setParam1(11927560);

			state = ChinBreakHandlerState.NULL;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		if (chatMessage.getType() == ChatMessageType.ENGINE && chatMessage.getMessage().contains("You can't log out until 10 seconds"))
		{
			int random = new IntRandomNumberGenerator(12, 20).nextInt();

			Single.just(1)
				.delaySubscription(random, TimeUnit.SECONDS)
				.subscribe((ignored) ->
					state = ChinBreakHandlerState.LOGOUT_BUTTON);
		}
	}

	private void handleLoginScreen()
	{
		Widget login = client.getWidget(WidgetID.LOGIN_CLICK_TO_PLAY_GROUP_ID, 87);

		if (login != null && login.getText().equals("CLICK HERE TO PLAY"))
		{
			state = ChinBreakHandlerState.LOGIN_MESSAGE_SCREEN;

			leftClickRandom();
		}
	}

	private void leftClickRandom()
	{
		executorService.submit(() ->
		{
			Point point = new Point(0, 0);

			mouseEvent(MouseEvent.MOUSE_PRESSED, point);
			mouseEvent(MouseEvent.MOUSE_RELEASED, point);
			mouseEvent(MouseEvent.MOUSE_FIRST, point);
		});
	}

	private void mouseEvent(int id, @NotNull Point point)
	{
		MouseEvent mouseEvent = new MouseEvent(
			client.getCanvas(), id,
			System.currentTimeMillis(),
			0, point.getX(), point.getY(),
			1, false, 1
		);

		client.getCanvas().dispatchEvent(mouseEvent);
	}

	@SuppressWarnings("SameParameterValue")
	private void sendKey(int key)
	{
		keyEvent(KeyEvent.KEY_PRESSED, key);
		keyEvent(KeyEvent.KEY_RELEASED, key);
	}

	private void keyEvent(int id, int key)
	{
		KeyEvent e = new KeyEvent(
			client.getCanvas(), id, System.currentTimeMillis(),
			0, key, KeyEvent.CHAR_UNDEFINED
		);

		client.getCanvas().dispatchEvent(e);
	}
}