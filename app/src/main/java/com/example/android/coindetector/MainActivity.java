package com.example.android.coindetector;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    static private final String TAG = "MAIN";
    static private final int REQUEST_IMAGE_CAPTURE = 1;
    static private final int IMG_SIZE = 700;
    static private final String IMAGE_PATH = "imagePath";
    static private final String STATUS = "status";
    static private final String NUM_TEXT_OBJECTS = "numTextObjects";
    static private final String TEXT_OBJECT_COLLECTION = "textObjects";
    static private final String RESULT_DIR = "result";
    static private final String IMAGE_DIR = "images";


    private ImageView mImageView;
    private Button mButton;
    private TextView mTextView;
    private ListenerRegistration mDatabaseListener;
    private DocumentReference mDocRef;
    private String mSavedImagePath;
    private Bitmap mBitmap;
    private String mFilename;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // initialize xml
        mImageView = findViewById(R.id.imageView);
        mButton = findViewById(R.id.detect);
        mTextView = findViewById(R.id.status);

        firebaseAuthentication();
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePicture();
            }
        });
    }

    private void firebaseAuthentication() {
        FirebaseAuth mAuth = FirebaseAuth.getInstance();
        mAuth.signInAnonymously()
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            // Sign in success, update UI with the signed-in user's information
                            Log.d(TAG, "signInAnonymously:success");
//                            FirebaseUser user = mAuth.getCurrentUser();
                        } else {
                            // If sign in fails, display a message to the user.
                            Log.w(TAG, "signInAnonymously:failure", task.getException());
                            Toast.makeText(MainActivity.this, "Authentication failed.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void takePicture() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            File image = null;
            try {
                image = createTempFile();
            } catch (IOException e) {
                Log.e(TAG, "onActivityResult: ", e);
            }

            // make sure image isn't empty
            if (image != null) {
                Uri imageURI = FileProvider.getUriForFile(
                        this,
                        "com.example.android.coindetector",
                        image);

                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageURI);
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            }
        }
    }

    private File createTempFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String filename = "coin_" + timeStamp;
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(filename, ".jpg", storageDir);

        // Save a file: path for use with ACTION_VIEW intents
        mSavedImagePath = image.getAbsolutePath();
        mFilename = "coin_" + timeStamp + ".jpg";
        return image;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            mButton.setEnabled(false);

            File imgFile = new File(mSavedImagePath);
            if (imgFile.exists()) {
                Log.d(TAG, "onActivityResult: success creating file");
                mBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                mBitmap = Bitmap.createScaledBitmap(mBitmap, IMG_SIZE, IMG_SIZE, false);
                uploadImage();
                mImageView.setImageBitmap(mBitmap);
            } else {
                Log.d(TAG, "onActivityResult: Error opening file");
            }
        }
    }

    private void uploadImage() {
        StorageReference coin = FirebaseStorage.getInstance()
                .getReference()
                .child(IMAGE_DIR)
                .child(mFilename);

        // convert bitmap to bytes and upload to cloud storage
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        mBitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
        byte[] data = byteArrayOutputStream.toByteArray();

        UploadTask uploadTask = coin.putBytes(data);
        uploadTask.addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                Log.e(TAG, "onFailure: failed to upload", e);
            }
        }).addOnCompleteListener(new OnCompleteListener<UploadTask.TaskSnapshot>() {
            @Override
            public void onComplete(@NonNull Task<UploadTask.TaskSnapshot> task) {
                Uri downloadUri = task.getResult().getDownloadUrl();
                Log.d(TAG, "onComplete: " + downloadUri);
            }
        });

        // add filename to firestore and set listener on the new document
        addToFirestore();
    }

    private void addToFirestore() {
        Map<String, Object> image = new HashMap<>();
        image.put(IMAGE_PATH, null);
        image.put(STATUS, "uploading...");

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection(RESULT_DIR).document(mFilename)
                .set(image)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Log.d(TAG, "onSuccess: added filename to firestore");
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.d(TAG, "onFailure: failed to add to firestore " + e);
                    }
                });

        // first initialization of document reference
        mDocRef = FirebaseFirestore.getInstance().collection("result").document(mFilename);

        mDatabaseListener = mDocRef.addSnapshotListener(new EventListener<DocumentSnapshot>() {
            @Override
            public void onEvent(DocumentSnapshot documentSnapshot, FirebaseFirestoreException e) {
                if (documentSnapshot.exists()) {
                    Log.d(TAG, "onEvent: document exists");
                    String status = documentSnapshot.getString(STATUS);
                    if (status.equals("Error, try again.")) {
                        mTextView.setText(R.string.server_error);
                        mButton.setEnabled(true);
                        mDatabaseListener.remove();
                    } else {
                        mTextView.setText(status);
                    }

                    String path = documentSnapshot.getString(IMAGE_PATH);
                    if (path != null)
                        display(path);
                } else if (e != null) {
                    Log.e(TAG, "onEvent: error with snapshot", e);
                }
            }
        });
    }

    private void display(String path) {
        Log.d(TAG, "displayImage: " + path);
        StorageReference coin = FirebaseStorage.getInstance().getReference().child(path);

        final long ONE_MB = 1024*1024;
        coin.getBytes(ONE_MB).addOnSuccessListener(new OnSuccessListener<byte[]>() {
            @Override
            public void onSuccess(byte[] bytes) {
                Log.d(TAG, "onSuccess: displaying image");
                mBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                // convert decoded image into a mutable image for text
                mBitmap = mBitmap.copy(Bitmap.Config.ARGB_8888, true);
                addLabels();
                mImageView.setImageBitmap(mBitmap);
                mDatabaseListener.remove(); // remove listener after displaying picture
                mButton.setEnabled(true);
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                Log.d(TAG, "onFailure: error displaying image");
            }
        });
    }

    private void addLabels() {
        // get the current image
        mDocRef.get().addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
            @Override
            public void onSuccess(DocumentSnapshot documentSnapshot) {
                if (documentSnapshot.exists()) {
                    Log.d(TAG, "onSuccess: testData document exists");
                    // get number of objects in the collection
                    int numTextObjects = documentSnapshot.getLong(NUM_TEXT_OBJECTS).intValue();
                    // iterate over every object in object collection
                    for (int i = 0; i < numTextObjects; ++i) {
                        DocumentReference textReference = mDocRef
                                .collection(TEXT_OBJECT_COLLECTION)
                                .document(Integer.toString(i));

                        // get the custom TextObject from the database
                        textReference.get().addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
                            @Override
                            public void onSuccess(DocumentSnapshot documentSnapshot) {
                                if (documentSnapshot.exists()) {
                                    Log.d(TAG, "onSuccess: textObject exists ");
                                    TextObject curTextObj = documentSnapshot.toObject(TextObject.class);
                                    drawText(
                                            curTextObj.getText(),
                                            curTextObj.getColor(),
                                            curTextObj.getX(),
                                            curTextObj.getY()
                                    );
                                }
                            }
                        });
                    }
                }
            }
        });
    }

    private void drawText(String text, String color, float x, float y) {
            Canvas canvas = new Canvas(mBitmap);
            Paint paint = new Paint();
            paint.setTextSize(30);
            paint.setColor(Color.parseColor(color));
            canvas.drawText(text, x, y, paint);
            mImageView.setImageBitmap(mBitmap);
    }
}
