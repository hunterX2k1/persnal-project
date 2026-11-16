"""Makes a made-up practice wiring diagram as a vector PDF (like a CAD export).

Used to test the diagram reader. Not a real MTU drawing.
Run:  python3 tools/make_practice_drawing.py app/src/main/assets/practice_drawing.pdf
Needs: pip install reportlab
"""
import sys
from reportlab.lib.pagesizes import A4, landscape
from reportlab.pdfgen import canvas

W, H = landscape(A4)
TOP, BOT = 500, 110  # supply rail and 0V rail
FS = 7


def txt(c, x, y, s, size=FS):
    c.setFont("Helvetica", size)
    c.drawString(x, y, s)


def wire(c, x1, y1, x2, y2):
    c.line(x1, y1, x2, y2)


def fuse(c, x, ytop, tag, pins=("1", "2")):
    """IEC fuse: small box with the wire running through. Occupies ytop-30 .. ytop."""
    wire(c, x, ytop, x, ytop - 8)
    c.rect(x - 3, ytop - 22, 6, 14, stroke=1, fill=0)
    wire(c, x, ytop - 8, x, ytop - 22)
    wire(c, x, ytop - 22, x, ytop - 30)
    txt(c, x + 8, ytop - 17, tag)
    txt(c, x - 9, ytop - 7, pins[0])
    txt(c, x - 9, ytop - 29, pins[1])
    return ytop - 30


def no_contact(c, x, ytop, tag, pins=("13", "14")):
    """Normally open contact: two short stubs and a slanted blade. ytop-30 .. ytop."""
    wire(c, x, ytop, x, ytop - 10)          # upper stub (part of wire)
    c.line(x, ytop - 10, x - 6, ytop - 21)  # blade (slanted)
    wire(c, x, ytop - 23, x, ytop - 30)     # lower stub
    txt(c, x + 8, ytop - 18, tag)
    txt(c, x - 13, ytop - 9, pins[0])
    txt(c, x - 13, ytop - 29, pins[1])
    return ytop - 30


def switch_no(c, x, ytop, tag, desc):
    no_contact(c, x, ytop, tag, ("1", "2"))
    c.line(x - 3, ytop - 15, x - 12, ytop - 15)  # push actuator
    txt(c, x + 8, ytop - 26, desc)
    return ytop - 30


def terminal(c, x, y, tag, num):
    """Terminal: small circle on the wire at y."""
    c.circle(x, y, 2.2, stroke=1, fill=0)
    txt(c, x + 5, y - 2, num)
    txt(c, x - 18, y - 2, tag)


def lamp(c, x, ytop, tag, desc, pins=("X1", "X2")):
    """Lamp: circle with a cross. ytop-24 .. ytop, wires touch top and bottom of circle."""
    cy = ytop - 12
    c.circle(x, cy, 7, stroke=1, fill=0)
    d = 4.9
    c.line(x - d, cy - d, x + d, cy + d)
    c.line(x - d, cy + d, x + d, cy - d)
    wire(c, x, ytop, x, ytop - 5)
    wire(c, x, ytop - 19, x, ytop - 24)
    txt(c, x + 11, cy + 1, tag)
    txt(c, x + 11, cy - 8, desc)
    txt(c, x - 14, ytop - 4, pins[0])
    txt(c, x - 14, ytop - 24, pins[1])
    return ytop - 24


def coil(c, x, ytop, tag, desc):
    """Relay coil: box drawn as four separate lines (like many CAD exports). ytop-24 .. ytop."""
    wire(c, x, ytop, x, ytop - 6)
    c.line(x - 8, ytop - 6, x + 8, ytop - 6)
    c.line(x - 8, ytop - 18, x + 8, ytop - 18)
    c.line(x - 8, ytop - 6, x - 8, ytop - 18)
    c.line(x + 8, ytop - 6, x + 8, ytop - 18)
    wire(c, x, ytop - 18, x, ytop - 24)
    txt(c, x + 12, ytop - 11, tag)
    txt(c, x + 12, ytop - 19, desc)
    txt(c, x - 13, ytop - 5, "A1")
    txt(c, x - 13, ytop - 25, "A2")
    return ytop - 24


def frame(c, title, sheet):
    c.setLineWidth(0.8)
    c.rect(20, 20, W - 40, H - 40, stroke=1, fill=0)
    c.rect(W - 260, 20, 240, 40, stroke=1, fill=0)
    txt(c, W - 250, 45, "PRACTICE DRAWING - made up, not a real MTU document", 6)
    txt(c, W - 250, 30, title + "   Sheet " + str(sheet), 8)
    c.setLineWidth(0.5)


def rails(c, x0, x1):
    wire(c, x0, TOP, x1, TOP)
    wire(c, x0, BOT, x1, BOT)
    txt(c, x0 - 22, TOP - 2, "+24V")
    txt(c, x0 - 16, BOT - 2, "0V")


def main(out):
    c = canvas.Canvas(out, pagesize=(W, H))
    c.setTitle("Practice drawing")

    # ---------- sheet 1: running lamp + relay coil ----------
    frame(c, "Engine running indication", 1)
    c.setLineWidth(0.5)
    rails(c, 60, 640)

    # H5 lamp circuit at x=150: 24V -> F3 -> K12:13-14 -> X2:5 -> H5 -> X2:6 -> 0V
    x = 150
    wire(c, x, TOP, x, 470)
    y = fuse(c, x, 470, "-F3")
    wire(c, x, y, x, 400)
    y = no_contact(c, x, 400, "-K12")
    wire(c, x, y, x, 320)
    terminal(c, x, 320, "-X2", "5")
    wire(c, x, 320, x, 260)
    y = lamp(c, x, 260, "-H5", "Engine running")
    wire(c, x, y, x, 180)
    terminal(c, x, 180, "-X2", "6")
    wire(c, x, 180, x, BOT)
    c.circle(x, TOP, 1.6, stroke=1, fill=1)  # junction dot on rail
    c.circle(x, BOT, 1.6, stroke=1, fill=1)

    # PLC A1 with output DO4 driving K12 coil at x=400; PLC fed via F2
    x = 400
    wire(c, x, TOP, x, 480)
    y = fuse(c, x, 480, "-F2")
    wire(c, x, y, x, 420)
    c.rect(370, 340, 80, 80, stroke=1, fill=0)  # PLC module box
    txt(c, 375, 408, "-A1")
    txt(c, 375, 398, "PLC")
    txt(c, x + 3, 412, "L+")
    txt(c, x + 3, 344, "DO4")
    wire(c, x, 340, x, 270)
    y = coil(c, x, 270, "-K12", "Running relay")
    wire(c, x, y, x, BOT)
    c.circle(x, TOP, 1.6, stroke=1, fill=1)
    c.circle(x, BOT, 1.6, stroke=1, fill=1)

    # A wire crossing the lamp circuit WITHOUT a dot (must NOT connect)
    wire(c, 100, 230, 250, 230)
    txt(c, 255, 228, "to sheet 2")
    c.showPage()

    # ---------- sheet 2: alarm lamp with push button, horn ----------
    frame(c, "Alarm", 2)
    c.setLineWidth(0.5)
    rails(c, 60, 640)

    x = 200
    wire(c, x, TOP, x, 470)
    y = fuse(c, x, 470, "-F7")
    wire(c, x, y, x, 410)
    y = switch_no(c, x, 410, "-S1", "Lamp test")
    wire(c, x, y, x, 300)
    y = lamp(c, x, 300, "-H7", "Alarm")
    wire(c, x, y, x, BOT)
    c.circle(x, TOP, 1.6, stroke=1, fill=1)
    c.circle(x, BOT, 1.6, stroke=1, fill=1)

    # Horn H8 shares F7 via a T-joint after the fuse
    wire(c, x, 425, 330, 425)
    c.circle(x, 425, 1.6, stroke=1, fill=1)
    wire(c, 330, 425, 330, 380)
    y = no_contact(c, 330, 380, "-K20", ("23", "24"))
    wire(c, 330, y, 330, 300)
    y = lamp(c, 330, 300, "-H8", "Alarm horn")
    wire(c, 330, y, 330, BOT)
    c.circle(330, BOT, 1.6, stroke=1, fill=1)
    c.showPage()
    c.save()


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "practice_drawing.pdf")
