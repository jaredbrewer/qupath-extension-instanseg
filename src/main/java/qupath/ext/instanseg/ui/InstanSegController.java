package qupath.ext.instanseg.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebView;
import org.commonmark.renderer.html.HtmlRenderer;
import org.controlsfx.control.CheckComboBox;
import org.controlsfx.control.PopOver;
import org.controlsfx.control.SearchableComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.instanseg.core.InstanSegModel;
import qupath.ext.instanseg.core.PytorchManager;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.fx.utils.FXUtils;
import qupath.lib.common.ThreadTools;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.gui.tools.WebViews;
import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.FutureTask;
import java.util.stream.IntStream;

/**
 * Controller for UI pane contained in instanseg_control.fxml
 */
public class InstanSegController extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(InstanSegController.class);

    private static final ResourceBundle resources = InstanSegResources.getResources();
    private final Watcher watcher;

    @FXML
    private CheckComboBox<ChannelSelectItem> comboChannels;
    @FXML
    private SearchableComboBox<InstanSegModel> modelChoiceBox;
    @FXML
    private Button runButton;
    @FXML
    private Button downloadButton;
    @FXML
    private Label labelMessage;
    @FXML
    private ChoiceBox<String> deviceChoices;
    @FXML
    private ChoiceBox<Integer> tileSizeChoiceBox;
    @FXML
    private Spinner<Integer> threadSpinner;
    @FXML
    private ToggleButton selectAllAnnotationsButton;
    @FXML
    private ToggleButton selectAllTMACoresButton;
    @FXML
    private CheckComboBox<OutputChannelItem> checkComboOutputs;
    @FXML
    private CheckBox makeMeasurementsCheckBox;
    @FXML
    private CheckBox randomColorsCheckBox;
    @FXML
    private Button infoButton;
    @FXML
    private Label modelDirLabel;
    @FXML
    private Label labelModelsLocation;
    @FXML
    private Tooltip tooltipModelDir;

    private final ExecutorService pool = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("instanseg", true));
    private final QuPathGUI qupath;
    private final ObjectProperty<FutureTask<?>> pendingTask = new SimpleObjectProperty<>();
    private MessageTextHelper messageTextHelper;

    private final BooleanProperty needsUpdating = new SimpleBooleanProperty();

    private final ReadOnlyObjectProperty<InstanSegModel> selectedModel = modelChoiceBox.getSelectionModel().selectedItemProperty();
    private final BooleanBinding selectedModelIsAvailable = InstanSegUtils.createModelDownloadedBinding(selectedModel, needsUpdating);

    private static final ObjectBinding<Path> modelDirectoryBinding = InstanSegUtils.getModelDirectoryBinding();

    private static final BooleanBinding isModelDirectoryValid = InstanSegUtils.isModelDirectoryValid(modelDirectoryBinding);

    /**
     * Create an instance of the InstanSeg GUI pane.
     * @param qupath The QuPath GUI it should be attached to.
     * @return A handle on the UI element.
     * @throws IOException If the FXML or resources fail to load.
     */
    public static InstanSegController createInstance(QuPathGUI qupath) throws IOException {
        return new InstanSegController(qupath);
    }

    private InstanSegController(QuPathGUI qupath) throws IOException {
        this.qupath = qupath;
        var url = InstanSegController.class.getResource("instanseg_control.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();
        watcher = new Watcher(modelChoiceBox);
// TODO: REMOVE THIS! JUST FOR TESTING!
InstanSegPreferences.modelDirectoryProperty().set(null);
        configureMessageLabel();
        configureDirectoryLabel();
        configureTileSizes();
        configureDeviceChoices();
        configureModelChoices();
        configureSelectButtons();
        configureRunning();
        configureThreadSpinner();
        infoButton.disableProperty().bind(selectedModelIsAvailable.not());
        downloadButton.disableProperty().bind(
                selectedModelIsAvailable.or(
                        selectedModel.isNull())
        );
        configureChannelPicker();
        configureOutputChannelCombo();
        configureDefaultValues();
    }

    private void configureOutputChannelCombo() {
        // Quick way to match widths...
        checkComboOutputs.prefWidthProperty().bind(comboChannels.widthProperty());
        // Show a better title than the text of all selections
        checkComboOutputs.getCheckModel().getCheckedItems().addListener(this::handleOutputChannelChange);
    }

    private void handleOutputChannelChange(ListChangeListener.Change<? extends OutputChannelItem> change) {
        var list = change.getList();
        if (list.isEmpty() || list.size() == checkComboOutputs.getItems().size())
            checkComboOutputs.setTitle("All available");
        else if (list.size() == 1) {
            checkComboOutputs.setTitle(list.getFirst().toString());
        } else {
            checkComboOutputs.setTitle(list.size() + " selected");
        }
    }
    

    private void configureDefaultValues() {
        makeMeasurementsCheckBox.selectedProperty().bindBidirectional(InstanSegPreferences.makeMeasurementsProperty());
        randomColorsCheckBox.selectedProperty().bindBidirectional(InstanSegPreferences.randomColorsProperty());
    }


    void interrupt() {
        watcher.interrupt();
    }

    /**
     * Open the model directory in the system file browser when double-clicked.
     * @param event
     */
    @FXML
    void handleModelDirectoryLabelClick(MouseEvent event) {
        if (event.getClickCount() != 2) {
            return;
        }
        var modelDir = InstanSegUtils.getModelDirectory().orElse(null);
        if (modelDir == null) {
            return;
        }
        if (Files.exists(modelDir)) {
            GuiTools.browseDirectory(modelDir.toFile());
        } else {
            logger.debug("Can't browse directory for {}", modelDir);
        }
    }

    @FXML
    void promptForModelDirectory() {
        promptToUpdateDirectory(InstanSegPreferences.modelDirectoryProperty());
    }


    private void configureChannelPicker() {
        updateChannelPicker(qupath.getImageData());
        qupath.imageDataProperty().addListener((v, o, n) -> updateChannelPicker(n));
        comboChannels.setTitle(getCheckComboBoxText(comboChannels));
        comboChannels.getItems().addListener((ListChangeListener<ChannelSelectItem>) c -> {
            comboChannels.setTitle(getCheckComboBoxText(comboChannels));
        });
        comboChannels.getCheckModel().getCheckedItems().addListener((ListChangeListener<ChannelSelectItem>) c -> {
            comboChannels.setTitle(getCheckComboBoxText(comboChannels));
        });
        FXUtils.installSelectAllOrNoneMenu(comboChannels);
        addSetFromVisible(comboChannels);
    }


    private void updateChannelPicker(ImageData<BufferedImage> imageData) {
        if (imageData == null) {
            return;
        }
        comboChannels.getCheckModel().clearChecks();
        comboChannels.getItems().clear();
        comboChannels.getItems().setAll(ChannelSelectItem.getAvailableChannels(imageData));
        if (imageData.isBrightfield()) {
            comboChannels.getCheckModel().checkIndices(IntStream.range(0, 3).toArray());
            var model = selectedModel.get();
            if (model != null && model.isDownloaded(Path.of(InstanSegPreferences.modelDirectoryProperty().get()))) {
                var modelChannels = model.getNumChannels();
                if (modelChannels.isPresent()) {
                    int nModelChannels = modelChannels.get();
                    if (nModelChannels != InstanSegModel.ANY_CHANNELS) {
                        comboChannels.getCheckModel().clearChecks();
                        comboChannels.getCheckModel().checkIndices(0, 1, 2);
                    }
                }

            }
        } else {
            comboChannels.getCheckModel().checkIndices(IntStream.range(0, imageData.getServer().nChannels()).toArray());
        }
    }

    private static String getCheckComboBoxText(CheckComboBox<ChannelSelectItem> comboBox) {
        int n = comboBox.getCheckModel().getCheckedItems().stream()
                .filter(Objects::nonNull)
                .toList()
                .size();
        if (n == 0)
            return resources.getString("ui.options.noChannelSelected");
        if (n == 1)
            return resources.getString("ui.options.oneChannelSelected");
        return String.format(resources.getString("ui.options.nChannelSelected"), n);
    }

    /**
     * Add an option to the ContextMenu of the CheckComboBox to select all
     * currently-visible channels.
     * <p>
     * Particularly useful for images with many channels - it's possible
     * to preview a subset of channels using the brightness and contrast
     * window, and to then transfer this selection to InstanSeg by simply
     * right-clicking and choosing "Set from visible".
     * @param comboChannels The CheckComboBox for selecting channels.
     */
    private void addSetFromVisible(CheckComboBox<ChannelSelectItem> comboChannels) {
        var mi = new MenuItem();
        mi.setText("Set from visible");
        mi.setOnAction(e -> {
            comboChannels.getCheckModel().clearChecks();
            var activeChannels = qupath.getViewer().getImageDisplay().selectedChannels();
            var channelNames = activeChannels.stream()
                    .map(ChannelDisplayInfo::getName)
                    .toList();
            if (qupath.getImageData() != null && !qupath.getImageData().getServer().isRGB()) {
                channelNames = channelNames.stream()
                        .map(s -> s.replaceAll(" \\(C\\d+\\)$", ""))
                        .toList();
            }
            var comboItems = comboChannels.getItems();
            for (int i = 0; i < comboItems.size(); i++) {
                if (channelNames.contains(comboItems.get(i).getName())) {
                    comboChannels.getCheckModel().check(i);
                }
            }
        });
        qupath.imageDataProperty().addListener((v, o, n) -> {
            if (n == null) {
                return;
            }
            mi.setDisable(n.getServer().isRGB());
        });
        if (qupath.getImageData() != null) {
            mi.setDisable(qupath.getImageData().getServer().isRGB());
        }
        comboChannels.getContextMenu().getItems().add(mi);
    }

    private void configureThreadSpinner() {
        SpinnerValueFactory.IntegerSpinnerValueFactory factory = (SpinnerValueFactory.IntegerSpinnerValueFactory) threadSpinner.getValueFactory();
        factory.setMax(Runtime.getRuntime().availableProcessors());
        threadSpinner.getValueFactory().valueProperty().bindBidirectional(InstanSegPreferences.numThreadsProperty().asObject());
    }

    private void configureRunning() {
        runButton.disableProperty().bind(
                qupath.imageDataProperty().isNull()
                        .or(pendingTask.isNotNull()) // Don't allow multiple tasks to be started
                        .or(messageTextHelper.hasWarning())
                        .or(deviceChoices.getSelectionModel().selectedItemProperty().isNull()) // Can't run without a device
                        .or(isModelDirectoryValid.not()) // Can't run or download without a model directory
                        .or(selectedModel.isNull()) // Can't run without a model
                        .or(Bindings.createBooleanBinding(() -> {
                            var model = selectedModel.get();
                            if (model == null) {
                                return true;
                            }
                            var modelDir = InstanSegUtils.getModelDirectory().orElse(null);
                            if (modelDir != null && !model.isDownloaded(modelDir)) {
                                return false; // to enable "download and run"
                            }
                            return false;
                        }, selectedModel, needsUpdating))
        );
        pendingTask.addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                pool.execute(newValue);
            }
        });
    }


    private void configureModelChoices() {
        addRemoteModels(modelChoiceBox.getItems());
        InstanSegPreferences.modelDirectoryProperty().addListener((v, o, n) -> {
            var oldModelDir = InstanSegUtils.tryToGetPath(o);
            if (oldModelDir != null && Files.exists(oldModelDir)) {
                watcher.unregister(oldModelDir);
            }
            handleModelDirectory(n);
        });
        selectedModel.addListener((v, o, n) -> refreshModelChoice());
        downloadButton.setOnAction(e -> downloadSelectedModelAsync());
        WebView webView = WebViews.create(true);
        PopOver infoPopover = new PopOver(webView);
        infoButton.setOnAction(e -> {
            parseMarkdown(selectedModel.get(), webView, infoButton, infoPopover);
        });
    }

    /**
     * Make UI changes based on the selected model.
     * This may be called when the selected model is changed, or an existing model is downloaded.
     */
    private void refreshModelChoice() {
        var model = modelChoiceBox.getSelectionModel().getSelectedItem();
        if (model == null)
            return;

        var modelDir = InstanSegUtils.getModelDirectory().orElse(null);
        boolean isDownloaded = modelDir != null && model.isDownloaded(modelDir);
        if (!isDownloaded || qupath.getImageData() == null) {
            return;
        }
        var numChannels = model.getNumChannels();
        if (qupath.getImageData().isBrightfield() && numChannels.isPresent() && numChannels.get() != InstanSegModel.ANY_CHANNELS) {
            comboChannels.getCheckModel().clearChecks();
            comboChannels.getCheckModel().checkIndices(0, 1, 2);
        }
        // Handle output channels
        var nOutputs = model.getOutputChannels().orElse(1);
        checkComboOutputs.getCheckModel().clearChecks();
        checkComboOutputs.getItems().setAll(OutputChannelItem.getOutputsForChannelCount(nOutputs));
        checkComboOutputs.getCheckModel().checkAll();
    }

    /**
     * Try to download the currently-selected model in another thread.
     * @return
     */
    private CompletableFuture<InstanSegModel> downloadSelectedModelAsync() {
        var model = selectedModel.get();
        if (model == null) {
            return CompletableFuture.completedFuture(null);
        }
        return downloadModelAsync(model);
    }

    /**
     * Try to download the specified model in another thread.
     * @param model
     * @return
     */
    private CompletableFuture<InstanSegModel> downloadModelAsync(InstanSegModel model) {
        var modelDir = InstanSegUtils.getModelDirectory().orElse(null);
        if (modelDir == null || !Files.exists(modelDir)) {
            Dialogs.showErrorMessage(resources.getString("title"),
                    resources.getString("ui.model-directory.choose-prompt"));
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> downloadModel(model, modelDir), ForkJoinPool.commonPool());
    }

    /**
     * Try to download the specified model to the given directory in the current thread.
     * @param model
     * @param modelDir
     * @return
     */
    private InstanSegModel downloadModel(InstanSegModel model, Path modelDir) {
        Objects.requireNonNull(modelDir);
        Objects.requireNonNull(model);
        try {
            Dialogs.showInfoNotification(resources.getString("title"),
                    String.format(resources.getString("ui.popup.fetching"), model.getName()));
            model.download(modelDir);
            Dialogs.showInfoNotification(resources.getString("title"),
                    String.format(resources.getString("ui.popup.available"), model.getName()));
            FXUtils.runOnApplicationThread(() -> {
                needsUpdating.set(!needsUpdating.get());
                refreshModelChoice();
            });
        } catch (IOException ex) {
            Dialogs.showErrorNotification(resources.getString("title"), resources.getString("error.downloading"));
        }
        return model;
    }

    private static void parseMarkdown(InstanSegModel model, WebView webView, Button infoButton, PopOver infoPopover) {
        Optional<String> readme = model.getREADME();
        if (readme.isEmpty())
            return;
        String body = readme.get();

        // Parse the initial markdown only, to extract any YAML front matter
        var parser = org.commonmark.parser.Parser.builder().build();
        var doc = parser.parse(body);

        // If the markdown doesn't start with a title, pre-pending the model title & description (if available)
        if (!body.startsWith("#")) {
            var sb = new StringBuilder();
            sb.append("## ").append(model.getName()).append("\n\n");
            sb.append("----\n\n");
            doc.prependChild(parser.parse(sb.toString()));
        }
        webView.getEngine().loadContent(HtmlRenderer.builder().build().render(doc));
        infoPopover.show(infoButton);
    }

    private static boolean promptToAllowOnlineModelCheck() {
        String always = resources.getString("ui.model-online-check.always");
        String never = resources.getString("ui.model-online-check.never");
        String prompt = resources.getString("ui.model-online-check.allow-once");
        var permit = Dialogs.builder()
                .title(resources.getString("title"))
                .contentText(resources.getString("ui.model-online-check.prompt"))
                .buttons(always, never, prompt)
                .showAndWait()
                .orElse(null);
        if (permit == null)
            return false;
        String text = permit.getText();
        if (always.equals(text)) {
            InstanSegPreferences.permitOnlineProperty().set(InstanSegPreferences.OnlinePermission.YES);
            return true;
        } else if (never.equals(text)) {
            InstanSegPreferences.permitOnlineProperty().set(InstanSegPreferences.OnlinePermission.NO);
            return false;
        } else if (prompt.equals(text)) {
            InstanSegPreferences.permitOnlineProperty().set(InstanSegPreferences.OnlinePermission.PROMPT);
            return true;
        } else {
            logger.warn("Unknown choice: {}", text);
            return false;
        }
    }

    private static void addRemoteModels(ObservableList<InstanSegModel> models) {
        var permit = InstanSegPreferences.permitOnlineProperty().get();
        if (permit == InstanSegPreferences.OnlinePermission.NO) {
            logger.debug("Not allowed to check for models online.");
            return;
        } else if (permit == InstanSegPreferences.OnlinePermission.PROMPT) {
            if (!promptToAllowOnlineModelCheck()) {
                logger.debug("User declined online model check.");
                return;
            }
        }
        var releases = GitHubUtils.getReleases(InstanSegUtils.getModelDirectory().orElse(null));
        if (releases.isEmpty()) {
            logger.info("No releases found.");
            return;
        }
        var release = releases.getFirst();
        var assets = GitHubUtils.getAssets(release);
        assets.forEach(asset -> {
            models.add(
                    InstanSegModel.fromURL(
                            asset.getName().replace(".zip", ""),
                            asset.getUrl())
            );
        });
    }


    private void configureTileSizes() {
        // The use of 32-bit signed ints for coordinates of the intermediate sparse matrix *might* be
        // an issue for very large tile sizes - but I haven't seen any evidence of this.
        // We definitely can't have very small tiles, because they must be greater than 2 x the padding.
        tileSizeChoiceBox.getItems().addAll(256, 512, 1024, 2048);
        tileSizeChoiceBox.getSelectionModel().select(Integer.valueOf(512));
        tileSizeChoiceBox.setValue(InstanSegPreferences.tileSizeProperty().getValue());
        tileSizeChoiceBox.valueProperty().addListener((v, o, n) -> InstanSegPreferences.tileSizeProperty().set(n));
    }

    private void configureSelectButtons() {
        selectAllAnnotationsButton.disableProperty().bind(qupath.imageDataProperty().isNull());
        selectAllTMACoresButton.disableProperty().bind(qupath.imageDataProperty().isNull());
        overrideToggleSelected(selectAllAnnotationsButton);
        overrideToggleSelected(selectAllTMACoresButton);
    }

    // Hack to prevent the toggle buttons from staying selected
    // This allows us to use a segmented button with the appearance of regular, non-toggle buttons
    private static void overrideToggleSelected(ToggleButton button) {
        button.selectedProperty().addListener((value, oldValue, newValue) -> button.setSelected(false));
    }

    private void handleModelDirectory(String n) {
        var path = InstanSegUtils.tryToGetPath(n);
        if (path == null)
            return;
        if (Files.exists(path) && Files.isDirectory(path)) {
            try {
                addModelsFromPath(path, modelChoiceBox);
                var localPath = InstanSegUtils.getLocalModelDirectory().orElse(null);
                if (localPath != null) {
                    if (!Files.exists(localPath)) {
                        Files.createDirectory(localPath);
                    }
                    watcher.register(localPath); // todo: unregister
                }
                addModelsFromPath(localPath, modelChoiceBox);
            } catch (IOException e) {
                logger.error("Unable to watch directory", e);
            }
        }
    }

    private void configureDeviceChoices() {
        deviceChoices.disableProperty().bind(Bindings.size(deviceChoices.getItems()).isEqualTo(0));
        updateAvailableDevices();
        // Don't bind property for now, since this would cause trouble if the InstanSegPreferences.preferredDeviceProperty() is
        // changed elsewhere
        deviceChoices.getSelectionModel().selectedItemProperty().addListener(
                (value, oldValue, newValue) -> InstanSegPreferences.preferredDeviceProperty().set(newValue));
    }

    private void updateAvailableDevices() {
        var available = PytorchManager.getAvailableDevices();
        deviceChoices.getItems().setAll(available);
        var selected = InstanSegPreferences.preferredDeviceProperty().get();
        if (available.contains(selected)) {
            deviceChoices.getSelectionModel().select(selected);
        } else {
            deviceChoices.getSelectionModel().selectFirst();
        }
    }

    private void configureMessageLabel() {
        messageTextHelper = new MessageTextHelper(modelChoiceBox, deviceChoices, comboChannels, needsUpdating);
        labelMessage.textProperty().bind(messageTextHelper.messageLabelText());
        if (messageTextHelper.hasWarning().get()) {
            labelMessage.getStyleClass().setAll("warning-message");
        } else {
            labelMessage.getStyleClass().setAll("standard-message");
        }
        messageTextHelper.hasWarning().addListener((observable, oldValue, newValue) -> {
            if (newValue)
                labelMessage.getStyleClass().setAll("warning-message");
            else
                labelMessage.getStyleClass().setAll("standard-message");
        });
    }

    private void configureDirectoryLabel() {
        isModelDirectoryValid.addListener((v, o, n) -> updateModelDirectoryLabel());
        updateModelDirectoryLabel();
    }

    private void updateModelDirectoryLabel() {
        if (isModelDirectoryValid.get()) {
            modelDirLabel.getStyleClass().setAll("standard-message");
            String modelPath = modelDirectoryBinding.get().toString();
            modelDirLabel.setText(modelPath);
            modelDirLabel.setCursor(Cursor.HAND);
            tooltipModelDir.setText(resources.getString("ui.options.directory.tooltip"));
            labelModelsLocation.setText(resources.getString("ui.options.directory-name"));
        } else {
            modelDirLabel.getStyleClass().setAll("warning-message");
            modelDirLabel.setCursor(Cursor.DEFAULT);
            modelDirLabel.setText(resources.getString("ui.options.directory-not-set"));
            tooltipModelDir.setText(resources.getString("ui.options.directory-not-set.tooltip"));
            labelModelsLocation.setText("");
        }
    }

    static void addModelsFromPath(Path path, ComboBox<InstanSegModel> box) {
        if (path == null || !Files.exists(path) || !Files.isDirectory(path))
            return;
        // See https://github.com/controlsfx/controlsfx/issues/1320
        box.setItems(FXCollections.observableArrayList());
        try (var ps = Files.list(path)) {
            for (var file: ps.toList()) {
                if (InstanSegModel.isValidModel(file)) {
                    box.getItems().add(InstanSegModel.fromPath(file));
                }
            }
        } catch (IOException e) {
            logger.error("Unable to list directory", e);
        }
    }

    void restart() {
        Thread.ofVirtual().start(watcher::processEvents);
    }

    @FXML
    private void runInstanSeg() {
        runInstanSeg(modelChoiceBox.getSelectionModel().getSelectedItem());
    }

    private void runInstanSeg(InstanSegModel model) {
        if (model == null) {
            Dialogs.showErrorNotification(resources.getString("title"), resources.getString("ui.error.no-model"));
            return;
        }
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorNotification(resources.getString("title"), resources.getString("error.no-imagedata"));
            return;
        }

        if (!PytorchManager.hasPyTorchEngine()) {
            if (!Dialogs.showConfirmDialog(resources.getString("title"), resources.getString("ui.pytorch"))) {
                Dialogs.showWarningNotification(resources.getString("title"), resources.getString("ui.pytorch-popup"));
                return;
            }
        }

        var modelPath = InstanSegUtils.getModelDirectory().orElse(null);
        if (modelPath == null) {
            Dialogs.showErrorNotification(resources.getString("title"), resources.getString("ui.model-directory.choose-prompt"));
            return;
        }

        if (!model.isDownloaded(modelPath)) {
            if (!Dialogs.showYesNoDialog(resources.getString("title"), resources.getString("ui.model-popup")))
                return;
            downloadModelAsync(model)
                    .thenAccept((InstanSegModel suppliedModel) -> {
                        if (suppliedModel == null || !suppliedModel.isDownloaded(modelPath)) {
                            Dialogs.showErrorNotification(resources.getString("title"), String.format(resources.getString("error.localModel")));
                        } else {
                            runInstanSeg(suppliedModel);
                        }
                    });
            return;
        }

        List<ChannelSelectItem> selectedChannels = comboChannels
                .getCheckModel().getCheckedItems()
                .stream()
                .filter(Objects::nonNull)
                .toList();
        int imageChannels = selectedChannels.size();
        var modelChannels = model.getNumChannels();
        if (modelChannels.isEmpty()) {
            Dialogs.showErrorNotification(resources.getString("title"), resources.getString("ui.error.model-not-downloaded"));
            return;
        }

        int nModelChannels = modelChannels.get();
        if (nModelChannels != InstanSegModel.ANY_CHANNELS && nModelChannels != imageChannels) {
            Dialogs.showErrorNotification(resources.getString("title"), String.format(
                    resources.getString("ui.error.num-channels-dont-match"),
                    nModelChannels, imageChannels));
            return;
        }

        // Create the tasks
        var device = deviceChoices.getSelectionModel().getSelectedItem();
        boolean makeMeasurements = makeMeasurementsCheckBox.isSelected();
        boolean randomColors = randomColorsCheckBox.isSelected();

        var task = new InstanSegTask(qupath.getImageData(), model, selectedChannels,
                checkComboOutputs.getCheckModel().getCheckedIndices(), device,
                makeMeasurements, randomColors);

        // Ensure PyTorch engine is available before running anything
        CompletableFuture.supplyAsync(this::ensurePyTorchAvailable, ForkJoinPool.commonPool())
                        .thenAccept((Boolean success) -> {
                            if (success) {
                                pendingTask.set(task);
                                // Reset the pending task when it completes (either successfully or not)
                                task.stateProperty().addListener((observable, oldValue, newValue) -> {
                                    if (Set.of(Worker.State.CANCELLED, Worker.State.SUCCEEDED, Worker.State.FAILED).contains(newValue)) {
                                        if (pendingTask.get() == task)
                                            pendingTask.set(null);
                                    }
                                });
                            }
                        });
    }

    private boolean ensurePyTorchAvailable() {
        if (PytorchManager.hasPyTorchEngine()) {
            return true;
        } else {
            downloadPyTorch();
            return PytorchManager.hasPyTorchEngine();
        }
    }


    private void downloadPyTorch() {
        FXUtils.runOnApplicationThread(() -> Dialogs.showInfoNotification(resources.getString("title"), resources.getString("ui.pytorch-downloading")));
        PytorchManager.getEngineOnline();
        FXUtils.runOnApplicationThread(this::updateAvailableDevices);
    }

    @FXML
    private void selectAllAnnotations() {
        var hierarchy = qupath.getImageData().getHierarchy();
        hierarchy.getSelectionModel().setSelectedObjects(hierarchy.getAnnotationObjects(), null);
    }

    @FXML
    private void selectAllTMACores() {
        var hierarchy = qupath.getImageData().getHierarchy();
        hierarchy.getSelectionModel().setSelectedObjects(hierarchy.getTMAGrid().getTMACoreList(), null);
    }

    private void promptToUpdateDirectory(StringProperty dirPath) {
        var modelDirPath = dirPath.get();
        var dir = modelDirPath == null || modelDirPath.isEmpty() ? null : new File(modelDirPath);
        if (dir != null) {
            if (dir.isFile())
                dir = dir.getParentFile();
            else if (!dir.exists())
                dir = null;
        }
        var newDir = FileChoosers.promptForDirectory(
                FXUtils.getWindow(modelDirLabel), // Get window from any node here
                resources.getString("ui.model-directory.choose-directory"),
                dir);
        if (newDir == null)
            return;
        dirPath.set(newDir.getAbsolutePath());
    }

}
