package flandmark

import org.bytedeco.javacpp.flandmark.{FLANDMARK_Model, flandmark_detect}
import org.bytedeco.javacpp.helper.opencv_core.CV_RGB
import org.bytedeco.javacpp.opencv_core._
import org.bytedeco.javacpp.opencv_imgproc.{CV_FILLED, cvCircle, cvRectangle}
import org.bytedeco.javacpp.opencv_objdetect.{CV_HAAR_DO_CANNY_PRUNING, CvHaarClassifierCascade, cvHaarDetectObjects}

object Detector {


  val storage = cvCreateMemStorage(0)
  cvClearMemStorage(storage)

  val searchScaleFactor = 1.1
  val flags = CV_HAAR_DO_CANNY_PRUNING
  val minFeatureSize = cvSize(40, 40)

  def detectFaceInImage(orig: IplImage, input: IplImage,
                        cascade: CvHaarClassifierCascade, model: FLANDMARK_Model,
                        bbox: Array[Int], landmarks: Array[Double]): Either[String, Unit] = {


    val rects = cvHaarDetectObjects(input, cascade, storage, searchScaleFactor, 2, flags, minFeatureSize, cvSize(0, 0))
    val nFaces = rects.total()

    if (nFaces == 0) Left("No faces detected")
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

      Right(())
    }
  }
}
