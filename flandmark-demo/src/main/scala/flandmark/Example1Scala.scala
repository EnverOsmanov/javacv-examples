package flandmark

import java.io.{File, FileNotFoundException, IOException}
import java.util.function.Supplier
import javax.swing.WindowConstants

import akka.NotUsed
import akka.actor.{ActorLogging, ActorSystem, DeadLetterSuppression, Props}
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.scaladsl.{Sink, Source}
import flandmark.WebcamFramePublisher.Continue
import org.bytedeco.javacpp.flandmark.{FLANDMARK_Model, flandmark_detect, flandmark_init}
import org.bytedeco.javacpp.helper.opencv_core.CV_RGB
import org.bytedeco.javacpp.opencv_core._
import org.bytedeco.javacpp.opencv_imgcodecs.cvLoadImage
import org.bytedeco.javacpp.opencv_imgproc._
import org.bytedeco.javacpp.opencv_objdetect.{CV_HAAR_DO_CANNY_PRUNING, CvHaarClassifierCascade, cvHaarDetectObjects, cvLoadHaarClassifierCascade}
import org.bytedeco.javacv.FrameGrabber.ImageMode
import org.bytedeco.javacv.{CanvasFrame, Frame, FrameGrabber, OpenCVFrameConverter}

import scala.util.{Failure, Success, Try}

object Example1Scala {

  val converter = new OpenCVFrameConverter.ToIplImage()
  val canvasOriginal = createCanvas("Example 1 - original")
  val canvasBW = createCanvas("Example 1 - BW input")
  val canvasOutput = createCanvas("Example 1 - output")

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

  private def loadImage(file: File): Try[IplImage] = {
    if (!file.exists) Failure(new FileNotFoundException(s"Image file does not exist: ${file.getAbsolutePath}"))
    else Option(cvLoadImage(file.getAbsolutePath)) match {
      case None => Failure(new IOException(s"Couldn't load image: ${file.getAbsolutePath}"))
      case Some(image) => Success(image)
    }
  }

  private def show(image: IplImage, canvas: CanvasFrame): Unit = {
    val image1 = cvCreateImage(cvGetSize(image), IPL_DEPTH_8U, image.nChannels)
    cvCopy(image, image1)

    canvas.showImage(converter.convert(image1))
  }

  def detectFaceInImage(orig: IplImage, input: IplImage,
                        cascade: CvHaarClassifierCascade, model: FLANDMARK_Model,
                        bbox: Array[Int], landmarks: Array[Double]): Try[Unit] = {
    val storage = cvCreateMemStorage(0)
    cvClearMemStorage(storage)

    val searchScaleFactor = 1.1
    val flags = CV_HAAR_DO_CANNY_PRUNING
    val minFeatureSize = cvSize(40, 40)
    val rects = cvHaarDetectObjects(input, cascade, storage, searchScaleFactor, 2, flags, minFeatureSize, cvSize(0, 0))
    val nFaces = rects.total()

    if (nFaces == 0) Failure(new Exception("No faces detected"))
    else {
      for (iface <- 0 until nFaces) {

        val elem = cvGetSeqElem(rects, iface)
        val rect = new CvRect(elem)

        bbox(0) = rect.x
        bbox(1) = rect.y
        bbox(2) = rect.x + rect.width
        bbox(3) = rect.y + rect.height

        flandmark_detect(input, bbox, model, landmarks)

        // display landmarks
        cvRectangle(orig, cvPoint(bbox(0), bbox(1)), cvPoint(bbox(2), bbox(3)), CV_RGB(255, 0, 0))
        cvRectangle(orig, cvPoint(model.bb.get(0).toInt, model.bb.get(1).toInt), cvPoint(model.bb.get(2).toInt, model.bb.get(3).toInt), CV_RGB(0, 0, 255))
        cvCircle(orig, cvPoint(landmarks(0).toInt, landmarks(1).toInt), 3, CV_RGB(0, 0, 255), CV_FILLED, 8, 0)

        for (i <- 2 until 2 * model.data.options.M by 2)
          cvCircle(orig, cvPoint(landmarks(i).toInt, landmarks(i + 1).toInt), 3, CV_RGB(255, 0, 0), CV_FILLED, 8, 0)
      }

      Success(())
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    Webcam.source
      .map(MediaConversion.frame2Ip1Image)
      .map(sss)
      .to(Sink.ignore)
      .run
  }

  import Webcam._

  def sss(image: IplImage): Unit = {

    val maybeError = for {
      faceCascade <- loadFaceCascade(faceCascadeFile)
      _ = println(s"Count: ${faceCascade.count}")

      model <- loadFLandmarkModel(flandmarkModelFile)
      _ = {
        println(s"Model w_cols: ${model.W}")
        println(s"Model w_rows: ${faceCascade.count}")
      }

      _ <- {
        show(image, canvasOriginal)

        val imageBW = cvCreateImage(cvGetSize(image), IPL_DEPTH_8U, 1)
        cvCvtColor(image, imageBW, CV_BGR2GRAY)
        show(imageBW, canvasBW)

        val bbox = Array.ofDim[Int](4)
        val landmarks = Array.ofDim[Double](2 * model.data.options.M())
        detectFaceInImage(image, imageBW, faceCascade, model, bbox, landmarks)
      }

      _ = show(image, canvasOutput)
    } yield ()

    maybeError.failed.foreach { case e: Exception => e.printStackTrace() }
  }

}

object Webcam {

  val inputImage = new File("face.jpg")
  val faceCascadeFile = new File("haarcascade_frontalface_alt.xml")
  val flandmarkModelFile = new File("flandmark_model.dat")

  def source(implicit as: ActorSystem): Source[Frame, NotUsed] = {
    val props = Props(new WebcamFramePublisher)

    val webcamActorRef = as.actorOf(props)
    val webcamActorPublisher = ActorPublisher[Frame](webcamActorRef)

    Source.fromPublisher(webcamActorPublisher)
  }
}

class WebcamFramePublisher extends ActorPublisher[Frame] with ActorLogging {

  private lazy val grabber = buildGrabber

  override def receive: Receive = {
    case _: Request => emitFrames()
    case Continue => emitFrames()
    case Cancel => onCompleteThenStop()
    case unexpectedMsg => log.warning(s"Unexpected message: $unexpectedMsg")
  }

  private def emitFrames(): Unit = {
    if (isActive && totalDemand > 0) {
      grabFrame().foreach(onNext)

      if (totalDemand > 0) self ! Continue
    }
  }

  private def grabFrame(): Option[Frame] = Option(grabber.grab())

  private def buildGrabber = {
    val g = FrameGrabber.createDefault(0)
    g.setImageWidth(640)
    g.setImageHeight(480)
    g.setImageWidth(CV_8U)
    g.setImageMode(ImageMode.COLOR)
    g.start()
    g
  }

}

object WebcamFramePublisher {
  private case object Continue extends DeadLetterSuppression
}

object MediaConversion {

  private val frame2Ip1ImageConverter = ThreadLocal.withInitial(new Supplier[OpenCVFrameConverter.ToIplImage] {
    def get(): OpenCVFrameConverter.ToIplImage = new OpenCVFrameConverter.ToIplImage
  })
  def frame2Ip1Image(mat: Frame): IplImage = frame2Ip1ImageConverter.get().convert(mat)
}
