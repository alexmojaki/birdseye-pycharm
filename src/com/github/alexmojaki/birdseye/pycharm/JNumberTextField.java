package com.github.alexmojaki.birdseye.pycharm;

import javax.swing.*;
import java.awt.event.KeyEvent;

public class JNumberTextField extends JTextField {

    @Override
    public void processKeyEvent(KeyEvent ev) {
        if (Character.isDigit(ev.getKeyChar())) {
            super.processKeyEvent(ev);
        }
        ev.consume();
    }

}