/*
 * Copyright (c) 2018-19 Marco Maccaferri and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.maccasoft.tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

import z80core.MemIoOps;
import z80core.Z80;

public class Machine extends MemIoOps {

    public final static int SIOA_C = 0x80;
    public final static int SIOA_D = 0x81;
    public final static int SIOB_C = 0x82;
    public final static int SIOB_D = 0x83;

    public final static int CF_DATA = 0x10;
    public final static int CF_FEATURES = 0x11;
    public final static int CF_ERROR = 0x11;
    public final static int CF_SECCOUNT = 0x12;
    public final static int CF_SECTOR = 0x13;
    public final static int CF_CYL_LOW = 0x14;
    public final static int CF_CYL_HI = 0x15;
    public final static int CF_HEAD = 0x16;
    public final static int CF_STATUS = 0x17;
    public final static int CF_COMMAND = 0x17;
    public final static int CF_LBA0 = 0x13;
    public final static int CF_LBA1 = 0x14;
    public final static int CF_LBA2 = 0x15;
    public final static int CF_LBA3 = 0x16;

    public final static int CF_READ_SEC = 0x20;
    public final static int CF_WRITE_SEC = 0x30;

    boolean page;
    byte[] rom;
    byte[] ram;

    byte cfCommand;
    byte[] cfLBA = new byte[4];
    File cfFile;
    RandomAccessFile cf;

    Z80 proc;
    Thread thread;
    double clockNs;

    public Machine() {
        rom = new byte[16384];
        ram = new byte[65536];

        clockNs = 1000.0 / 7.3728;

        proc = new Z80(this, null);

        thread = new Thread(new Runnable() {

            @Override
            public void run() {
                Machine.this.run();
            }
        });
    }

    public void setRom(int address, File file) throws IOException {
        InputStream is = new FileInputStream(file);
        is.read(rom, address, rom.length - address);
        is.close();
    }

    public void setRom(int address, byte[] rom) throws IOException {
        System.arraycopy(rom, 0, this.rom, address, Math.min(this.rom.length - address, rom.length));
    }

    public void setClock(double freq) {
        clockNs = 1000.0 / freq;
    }

    public void setCompactFlash(File file) {
        this.cfFile = file;
    }

    public void start() {
        try {
            if (cfFile != null) {
                cf = new RandomAccessFile(cfFile, "rw");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        thread.start();
    }

    protected void run() {
        long ns = System.nanoTime();

        while (!Thread.interrupted()) {
            synchronized (proc) {
                int runTstates = (int) ((System.nanoTime() - ns) / clockNs);
                if (runTstates >= 4) {
                    long prevTstates = tstates;
                    while (tstates < (prevTstates + runTstates)) {
                        proc.execute();
                    }
                    ns += (tstates - prevTstates) * clockNs;
                }
            }

            try {
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    @Override
    public void reset() {
        synchronized (proc) {
            page = false;
            tstates = 0;
            proc.reset();
            super.reset();
        }
    }

    @Override
    public int fetchOpcode(int address) {
        tstates += 4; // 3 clocks to fetch opcode from RAM and 1 execution clock
        if (!page && address < rom.length) {
            return rom[address & 0xFFFF] & 0xff;
        }
        return ram[address & 0xFFFF] & 0xff;
    }

    @Override
    public int inPort(int port) {
        tstates += 4; // 4 clocks for read byte from bus

        switch (port & 0xFF) {
            case CF_DATA:
                if (cfCommand == CF_READ_SEC) {
                    try {
                        if (cf != null) {
                            return (byte) cf.read();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                break;
            case CF_STATUS:
                return 0x40; // CF ready
            case SIOA_C:
                return 0x04; // Always return TX buffer empty
            case SIOB_C:
                return 0x04; // Always return TX buffer empty
        }

        return 0;
    }

    @Override
    public void outPort(int port, int value) {
        tstates += 4; // 4 clocks for write byte to bus

        switch (port & 0xFF) {
            case CF_DATA:
                if (cfCommand == CF_WRITE_SEC) {
                    try {
                        if (cf != null) {
                            cf.write(value & 0xFF);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                break;
            case CF_COMMAND:
                cfCommand = (byte) value;
                if (cfCommand == CF_WRITE_SEC || cfCommand == CF_READ_SEC) {
                    try {
                        long addr = ((cfLBA[3] & 0x0F) << 24) | ((cfLBA[2] & 0xFF) << 16) | ((cfLBA[1] & 0xFF) << 8) | (cfLBA[0] & 0xFF);
                        if (cf != null) {
                            cf.seek(addr << 9);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                break;
            case CF_LBA0:
                cfLBA[0] = (byte) value;
                break;
            case CF_LBA1:
                cfLBA[1] = (byte) value;
                break;
            case CF_LBA2:
                cfLBA[2] = (byte) value;
                break;
            case CF_LBA3: {
                cfLBA[3] = (byte) value;
                break;
            }
            case 0x38: // ROM page
                page = !page;
                break;
        }
    }

    @Override
    public int peek8(int address) {
        tstates += 3; // 3 clocks for read byte from RAM
        if (!page && address < rom.length) {
            return rom[address & 0xFFFF] & 0xff;
        }
        return ram[address & 0xFFFF] & 0xff;
    }

    @Override
    public void poke8(int address, int value) {
        tstates += 3; // 3 clocks for write byte to RAM
        if (page || address >= rom.length) {
            ram[address & 0xFFFF] = (byte) value;
        }
    }

    @Override
    public long getTstates() {
        return tstates;
    }

    public void stop() {
        try {
            thread.interrupt();
        } catch (Exception e) {
            // Do nothing
        }
        try {
            if (cf != null) {
                cf.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
