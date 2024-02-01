package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.BaseNDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.training.util.ProgressBar;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.experimental.pixels.OpenCVProcessor;
import qupath.lib.experimental.pixels.OutputHandler;
import qupath.lib.images.servers.PixelType;
import qupath.lib.objects.utils.Tiler;
import qupath.lib.scripting.QP;
import qupath.opencv.ops.ImageOps;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static qupath.lib.gui.scripting.QPEx.createTaskRunner;

public class InstanSegCommand {
    private static final Logger logger = LoggerFactory.getLogger(InstanSegCommand.class);

    public static void runInstanSeg() {
        if (QP.getCurrentImageData() == null) {
            Dialogs.showErrorNotification("Instanseg", "No image open!");
            return;
        }
        if (Objects.requireNonNull(QP.getSelectedObjects()).isEmpty()) {
            Dialogs.showErrorNotification("Instanseg", "No annotation selected!");
        }

        // Specify device
        String deviceName = "gpu"; // "mps", "cuda"
//			deviceName = "gpu";

        // May need to reduce threads to avoid trouble (especially if using mps/cuda)
        // int nThreads = qupath.lib.common.ThreadTools.getParallelism()
        int nThreads = 1;
        logger.info("Using $nThreads threads");
        int nPredictors = 1;

        // TODO: Set path!
        var path = "/home/alan/Documents/github/imaging/models/instanseg_39107731.pt";
        var imageData = QP.getCurrentImageData();

        double downsample = 0.5 / imageData.getServer().getPixelCalibration().getAveragedPixelSize().doubleValue();

        int inputWidth = 256;
        int inputHeight = inputWidth;
        int padding = 16;
        // Optionally pad images to the required size
        boolean padToInputSize = true;
        String layout = "CHW";

        // TODO: Remove C if not needed (added for instanseg_v0_2_0.pt)
        String layoutOutput = "CHW";


        var device = Device.fromName(deviceName);

        try (var model = Criteria.builder()
                .setTypes(Mat.class, Mat.class)
                .optModelUrls(path)
                .optProgress(new ProgressBar())
                .optDevice(device) // Remove this line if devices are problematic!
                .optTranslator(new MatTranslator(layout, layoutOutput))
                .build()
                .loadModel()) {

            BaseNDManager baseManager = (BaseNDManager)model.getNDManager();

            printResourceCount("Resource count before prediction", (BaseNDManager)baseManager.getParentManager());
            baseManager.debugDump(2);

            BlockingQueue<Predictor<Mat, Mat>> predictors = new ArrayBlockingQueue<>(nPredictors);

            try {
                for (int i = 0; i < nPredictors; i++)
                    predictors.put(model.newPredictor());

                printResourceCount("Resource count after creating predictors", (BaseNDManager)baseManager.getParentManager());

                var preprocessing = ImageOps.Core.sequential(
                        ImageOps.Core.ensureType(PixelType.FLOAT32),
                        // ImageOps.Core.divide(255.0)
                        ImageOps.Normalize.percentile(1, 99, true, 1e-6)
                );
                var predictionProcessor = new TilePredictionProcessor(predictors, baseManager,
                        layout, layoutOutput, preprocessing, inputWidth, inputHeight, padToInputSize);
                var processor = OpenCVProcessor.builder(predictionProcessor)
                        // .tiler(Tiler.builder(inputWidth-padding*2, inputHeight-padding*2)
                        .tiler(Tiler.builder((int)(downsample * inputWidth-padding*2), (int)(downsample * inputHeight-padding*2))
                                .alignTopLeft()
                                .cropTiles(false)
                                .build()
                        )
                        .outputHandler(OutputHandler.createObjectOutputHandler(new OutputToObjectConvert()))
                        .padding(padding)
                        .mergeSharedBoundaries(0.25)
                        .downsample(downsample)
                        .build();
                var runner = createTaskRunner(nThreads);
                processor.processObjects(runner, imageData, QP.getSelectedObjects());
            } finally {
                for (var predictor: predictors) {
                    predictor.close();
                }
            }
            printResourceCount("Resource count after prediction", (BaseNDManager)baseManager.getParentManager());
        } catch (ModelNotFoundException | MalformedModelException |
                 IOException | InterruptedException ex) {
            Dialogs.showErrorMessage("Unable to run InstanSeg", ex);
            logger.error("Unable to run InstanSeg", ex);
        }
        QP.fireHierarchyUpdate();
    }

    private static void printResourceCount(String title, BaseNDManager manager) {
        manager.debugDump(2);
    }
}