//
// Created for SmartCropper SSIM implementation
//

#include "include/SSIMCalculator.h"
#include <opencv2/opencv.hpp>

using namespace cv;
using namespace ssim;

Mat SSIMCalculator::convertToGray(const Mat& img) {
    Mat gray;
    if (img.channels() == 3) {
        cvtColor(img, gray, COLOR_BGR2GRAY);
    } else if (img.channels() == 4) {
        cvtColor(img, gray, COLOR_BGRA2GRAY);
    } else {
        gray = img.clone();
    }
    return gray;
}

double SSIMCalculator::calculateSSIM(const Mat& img1, const Mat& img2) {
    if (img1.empty() || img2.empty()) {
        return 0.0;
    }
    
    // Convert to grayscale if needed
    Mat gray1 = convertToGray(img1);
    Mat gray2 = convertToGray(img2);
    
    // Resize images to same size if different
    if (gray1.size() != gray2.size()) {
        Size targetSize = gray1.size();
        if (gray2.cols * gray2.rows > gray1.cols * gray1.rows) {
            targetSize = gray2.size();
        }
        resize(gray1, gray1, targetSize);
        resize(gray2, gray2, targetSize);
    }
    
    Scalar mssim = getMSSIM(gray1, gray2);
    return mssim[0]; // Return the SSIM value
}

Scalar SSIMCalculator::getMSSIM(const Mat& i1, const Mat& i2) {
    const double C1 = 6.5025, C2 = 58.5225;
    
    int d = CV_32F;
    
    Mat I1, I2;
    i1.convertTo(I1, d);
    i2.convertTo(I2, d);
    
    Mat I2_2 = I2.mul(I2);        // I2^2
    Mat I1_2 = I1.mul(I1);        // I1^2
    Mat I1_I2 = I1.mul(I2);       // I1 * I2
    
    Mat mu1, mu2;
    GaussianBlur(I1, mu1, Size(11, 11), 1.5);
    GaussianBlur(I2, mu2, Size(11, 11), 1.5);
    
    Mat mu1_2 = mu1.mul(mu1);
    Mat mu2_2 = mu2.mul(mu2);
    Mat mu1_mu2 = mu1.mul(mu2);
    
    Mat sigma1_2, sigma2_2, sigma12;
    
    GaussianBlur(I1_2, sigma1_2, Size(11, 11), 1.5);
    sigma1_2 -= mu1_2;
    
    GaussianBlur(I2_2, sigma2_2, Size(11, 11), 1.5);
    sigma2_2 -= mu2_2;
    
    GaussianBlur(I1_I2, sigma12, Size(11, 11), 1.5);
    sigma12 -= mu1_mu2;
    
    Mat t1, t2, t3;
    
    t1 = 2 * mu1_mu2 + C1;
    t2 = 2 * sigma12 + C2;
    t3 = t1.mul(t2);
    
    t1 = mu1_2 + mu2_2 + C1;
    t2 = sigma1_2 + sigma2_2 + C2;
    t1 = t1.mul(t2);
    
    Mat ssim_map;
    divide(t3, t1, ssim_map);
    
    Scalar mssim = mean(ssim_map);
    return mssim;
}