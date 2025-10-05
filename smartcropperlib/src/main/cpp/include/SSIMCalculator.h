//
// Created for SmartCropper SSIM implementation
//

#ifndef SMART_CROPPER_SSIM_CALCULATOR_H
#define SMART_CROPPER_SSIM_CALCULATOR_H

#include <opencv2/opencv.hpp>

namespace ssim {
    class SSIMCalculator {
    public:
        static double calculateSSIM(const cv::Mat& img1, const cv::Mat& img2);
        static cv::Scalar getMSSIM(const cv::Mat& i1, const cv::Mat& i2);
        
    private:
        static cv::Mat convertToGray(const cv::Mat& img);
    };
}

#endif //SMART_CROPPER_SSIM_CALCULATOR_H