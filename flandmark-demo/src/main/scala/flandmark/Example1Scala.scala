package flandmark

import java.io.{File, FileNotFoundException, IOException}
import javax.swing.WindowConstants

import akka.NotUsed
import akka.actor.{ActorSystem, DeadLetterSuppression}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{ActorMaterializer, Attributes, Outlet, SourceShape}
import flandmark.Detector.detectFaceInImage
import flandmark.Webcam.{faceCascadeFile, flandmarkModelFile}
import org.bytedeco.javacpp.flandmark.{FLANDMARK_Model, flandmark_init}
import org.bytedeco.javacpp.opencv_core._
import org.bytedeco.javacpp.opencv_imgproc._
import org.bytedeco.javacpp.opencv_objdetect.{CvHaarClassifierCascade, cvLoadHaarClassifierCascade}
import org.bytedeco.javacv.FrameGrabber.ImageMode
import org.bytedeco.javacv.{CanvasFrame, Frame, FrameGrabber, OpenCVFrameConverter}

import scala.util.{Failure, Success, Try}

object Example1Scala {

  val converter = new OpenCVFrameConverter.ToIplImage()
/*  val canvasOriginal = createCanvas("Example 1 - original")
  val canvasBW = createCanvas("Example 1 - BW input")*/
  private val canvasOutput = createCanvas("Example 1 - output")


  private def createCanvas(title: String) = {
    val canvas = new CanvasFrame(title, 1)
    canvas.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
    canvas
  }

  private def loadFaceCascade(file: File): Try[CvHaarClassifierCascade] = {
    if (!file.exists) {
      Failure(new FileNotFoundException(s"Face cascade file does not exist: ${file.getAbsolutePath}"))
    }
    else {
      Option(cvLoadHaarClassifierCascade(file.getCanonicalPath, cvSize(0, 0))) match {
        case None => Failure(new IOException(s"Failed to load face cascade from file: ${file.getAbsolutePath}"))
        case Some(faceCascade) => Success(faceCascade)
      }
    }
  }

  private def loadFLandmarkModel(file: File): Try[FLANDMARK_Model] = {
    if (!file.exists) Failure(new FileNotFoundException(s"FLandmark model file does not exist: ${file.getAbsolutePath}"))
    else Option(flandmark_init("flandmark_model.dat")) match {
      case None => Failure(new IOException(s"Failed to load FLandmark model from file: ${file.getAbsolutePath}"))
      case Some(model) => Success(model)
    }
  }


  private def show(image: IplImage, canvas: CanvasFrame): Unit = {
    val image1 = cvCreateImage(cvGetSize(image), IPL_DEPTH_8U, image.nChannels)
    cvCopy(image, image1)

    canvas.showImage(converter.convert(image1))
    cvReleaseImage(image1)
  }



  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val maybeError = for {
      faceCascade <- loadFaceCascade(faceCascadeFile)
      _ = println(s"Count: ${faceCascade.count}")

      model <- loadFLandmarkModel(flandmarkModelFile)
      _ = {
        println(s"Model w_cols: ${model.W}")
        println(s"Model w_rows: ${faceCascade.count}")
      }
    } yield {


      val bbox = Array.ofDim[Int](4)
      val landmarks = Array.ofDim[Double](2 * model.data.options.M())

      Webcam.source
        .map(frame => sss(frame, model, faceCascade, bbox, landmarks))
        .to(Sink.ignore)
        .run
    }


    maybeError.failed.foreach{ e: Throwable => e.printStackTrace() }

  }

  def sss(frame: Frame, model: FLANDMARK_Model, faceCascade: CvHaarClassifierCascade, bbox: Array[Int], landmarks: Array[Double]): Unit = {

    // show(image, canvasOriginal)
    val image = MediaConversion.frame2Ip1Image(frame)
    val imageBW = cvCreateImage(cvGetSize(image), IPL_DEPTH_8U, 1)
    cvCvtColor(image, imageBW, CV_BGR2GRAY)
    // show(imageBW, canvasBW)

    val maybeError: Either[String, Unit] = for {

      _ <- detectFaceInImage(image, imageBW, faceCascade, model, bbox, landmarks).right

    } yield {
      show(image, canvasOutput)
    }

    maybeError.left foreach println
    cvReleaseImage(imageBW)
  }

}

object Webcam {

  val inputImage = new File("face.jpg")
  val faceCascadeFile = new File("haarcascade_frontalface_alt.xml")
  val flandmarkModelFile = new File("flandmark_model.dat")

  def source(implicit as: ActorSystem): Source[Frame, NotUsed] = {

    Source.fromGraph(new WebcamGraphS)
  }
}

class WebcamGraphS extends GraphStage[SourceShape[Frame]] {
  val out: Outlet[Frame] = Outlet("NumbersSource")
  override val shape: SourceShape[Frame] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      // All state MUST be inside the GraphStageLogic,
      // never inside the enclosing GraphStage.
      // This state is safe to access and modify from all the
      // callbacks that are provided by GraphStageLogic and the
      // registered handlers.
      private val grabber = buildGrabber
      private def grabFrame(): Option[Frame] = Option(grabber.grab())

      private def buildGrabber = {

        println("Building...")
        val g = FrameGrabber.createDefault(0)
        g.setImageWidth(640)
        g.setImageHeight(480)
        g.setImageWidth(CV_8U)
        g.setImageMode(ImageMode.COLOR)
        g.start()
        g
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          grabFrame().foreach(frame => push(out, frame))
        }
      })
    }
}

object MediaConversion {

  private val frame2Ip1ImageConverter = new OpenCVFrameConverter.ToIplImage
  def frame2Ip1Image(mat: Frame): IplImage = frame2Ip1ImageConverter.convert(mat)
}
