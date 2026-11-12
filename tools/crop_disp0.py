from PIL import Image

im = Image.open('shots/disp0.png')
crop = im.crop((100, 300, 1100, 1200))
crop.save('shots/crop_disp0.png')
