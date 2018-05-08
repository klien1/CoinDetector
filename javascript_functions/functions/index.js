/*
 * 4/21/2018
 * Kevin Lien
*/

'use strict';

const functions = require('firebase-functions');
const gcs = require('@google-cloud/storage');
const admin = require('firebase-admin');
const exec = require('child_process').exec;
const path = require('path');
const fs = require('fs');
const os = require('os');
const google = require('googleapis');
const sizeOf = require('image-size');

admin.initializeApp();
const db = admin.firestore();

function predict(base64string) {
    return new Promise((resolve, reject) => {
        google.auth.getApplicationDefault((err, authClient) => {
            if (err)
                reject(err);

            // get authentication to cloud services
            if (authClient.createScopedRequired && authClient.createScopedRequired()) {
                authClient = authClient.createScoped([
                    'https://www.googleapis.com/auth/cloud-platform'
                ]);
            }

            // ml.googleapis.com/${version}
            var ml = google.ml({
                version: 'v1'
            });

            // name is required
            const request = {
                auth: authClient,
                name: 'projects/PROJECT_NAME/models/MODEL_NAME',
                resource: {
                    instances: [
                        { "inputs": { "b64": base64string } } 
                    ]
                }
            };

            ml.projects.predict(request, (err, result) => {
                if (err)
                    reject(err);
                else
                    resolve(result);
            });
        });
    });
}

exports.classifyImage = functions.storage.object().onFinalize((object) => {
    
    // initialize paths
    const fileBucket = object.bucket; // bucketName
    const bucket = gcs().bucket(fileBucket);
    const contentType = object.contentType; // images/jpeg
    const filePath = object.name; // folder/imgName
    const file = bucket.file(filePath); // to download to tmp
    const fileName = path.basename(filePath);
    
    const imageRef = db.collection('result').doc(fileName);
    const outputPath = `images_with_bounding_box/${fileName}`;
    
    // filter for images
    if (!contentType.startsWith('image/')) {
        // console.log(contentType, ' is not an image file');
        return null;
    }

    // filter for images folder
    if (!filePath.startsWith('images/')) {
        // console.log(filePath, ' is the wrong directory');
        return null;
    }

    const tempDestination = path.join(os.tmpdir(), fileName);

    // move image to tmp destination for preproccessing
    // console.log('downloading to tmp...');
    return file.download({
        destination: tempDestination
    }).then(() => {
        // console.log('encoding to base64...');
        const bitmap = fs.readFileSync(tempDestination);
        const buffer = new Buffer(bitmap).toString('base64');
        return buffer;
    }).then((base64string) => {
        // console.log('waiting for prediction...');
        imageRef.update({
            status: 'waiting for prediction...'
        });
        return predict(base64string);
    }).then((result) => {
        // console.log('processing results...');
        imageRef.update({
            status: 'processing results...'
        });
        
        // reference to collection where text objects are stored
        const textRef = imageRef.collection('textObjects');

        // only classifying 1 img which is index 0
        const curImg = result.predictions[0];
        // console.log('curImg', curImg);
        // console.log('confidence', curImg.detection_scores);
        // console.log('classification', curImg.detection_classes);
        
        
        // keeps track of number of number of current coin index
        let count = 0;
        
        // sum of all the coins
        let sum = 0;
        
        // keeps track of number of coins above confidence
        let numCoins = 0;
        // scores are ordered from greatest to least
        for (let i = 0; i < curImg.detection_scores.length; ++i) {
            // once confidence below 80% is reached, exit the loop
            if (curImg.detection_scores[i] < 0.8)
                break;
            ++numCoins;
        }
        
        // detection_boxes == bounding box
        // detection_scores == confidence
        // detection_classes == classification
        
        // sizeof { height: 700, width: 700, type: 'jpg' }
        const imgSize = sizeOf(tempDestination);

        let imgMagickArgs = [`convert ${ tempDestination }`];

        // draw bounding box for each coin
        for (let i = 0; i < numCoins; ++i) {
            const curImgBox = curImg.detection_boxes[i]; 
            const topLeftY = curImgBox[0] * imgSize.height;
            const topLeftX = curImgBox[1] * imgSize.width;
            const bottomRightY = curImgBox[2] * imgSize.height;
            const bottomRightX = curImgBox[3] * imgSize.width;
            
            // current coin classification
            const classification = curImg.detection_classes[i];
            // const conf = (curImg.detection_scores[i]*100).toFixed(2).toString() + '%';
            // const conf = (curImg.detection_scores[i]).toFixed(4);
            let color;
            let coinName;
            
            switch(classification) {
                case 1:
                    coinName = 'Penny';
                    // SkyBlue
                    color = '#00BFFF';
                    sum += 0.01;
                    break;
                case 2:
                    coinName = 'Nickel';
                    // light pink
                    color = '#FF3E96';
                    sum += 0.05;
                    break;
                case 3:
                    coinName = 'Dime';
                    // lime
                    color = '#00FF00';
                    sum += 0.10;
                    break;
                case 4:
                    coinName = 'Quarter';
                    // light cyan
                    color = '#E0FFFF';
                    sum += 0.25;
                    break;
                default:
                    coinName = 'Error';
                    // red
                    color = '#FF0000';
            }

            // add new doc for each text object
            textRef.doc(count.toString()).set({
                text: `${coinName}`,
                color: `${color}`,
                x: topLeftX,
                y: topLeftY
            });
            ++count;

            // adds argument for drawing bounding circle for classifed coin
            const args = `-stroke '${color}' -strokewidth 4 -fill none -draw "arc ${topLeftX},${topLeftY},${bottomRightX},${bottomRightY} 0,360"`;
            imgMagickArgs.push(args);
        }

        // adds the destination of image magick convert
        imgMagickArgs.push(tempDestination);
        // console.log(imgMagickArgs);
        // console.log('drawing bounding boxes...');

        return new Promise((resolve, reject) => {
            exec(imgMagickArgs.join(' '), (err) => {
                if (err) {
                    // console.log('error in drawing box', err);
                    reject(err);
                }
                else {
                    // console.log('success with drawing box');
                    // merge adds to existing document
                    imageRef.set({
                        numTextObjects: count
                    }, {merge: true });
                    const money = sum.toFixed(2).toString();
                    resolve(money);
                }
            });
        });
    }).then((money) => {
        // console.log('uploading image to outputPath');
        imageRef.update({
            status: `Change: $${money}`
        });
        return bucket.upload(tempDestination, { destination: outputPath });
    }).then(() => {
        // console.log('updating database with image path');
        return imageRef.update({
                imagePath: outputPath
            });
    }).catch(err => {
        console.log('Error occurred: ', err);
        imageRef.update({
            status: 'Error, try again.'
        });
    });
});