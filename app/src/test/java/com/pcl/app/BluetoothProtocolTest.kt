package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class BluetoothProtocolTest {
    @Test fun callerDetailsSurviveUtf8FrameRoundTrip() {
        val msg = "CALL|RINGING|+230 5555 1234|Zoë|INCOMING"
        val bytes = msg.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        FrameWriter(output).writeCmd(msg)
        val input = DataInputStream(ByteArrayInputStream(output.toByteArray()))
        assertEquals(Protocol.TYPE_CMD, input.readUnsignedByte())
        assertEquals(bytes.size, input.readInt())
        assertEquals(msg, String(ByteArray(bytes.size).also(input::readFully), Charsets.UTF_8))
        assertEquals(-1, input.read())
    }

    @Test fun separateCommandsRemainSeparateFrames() {
        val output = ByteArrayOutputStream()
        val writer = FrameWriter(output)
        writer.writeCmd("CALL|RINGING||")
        writer.writeCmd("CALL|RINGING|55551234|Alice")
        val input = DataInputStream(ByteArrayInputStream(output.toByteArray()))
        for (expected in listOf("CALL|RINGING||", "CALL|RINGING|55551234|Alice")) {
            assertEquals(Protocol.TYPE_CMD, input.readUnsignedByte())
            val bytes = ByteArray(input.readInt())
            input.readFully(bytes)
            assertEquals(expected, String(bytes, Charsets.UTF_8))
        }
        assertEquals(-1, input.read())
    }

    @Test fun callStateIsSentOnlyFromGatewayAndCommandsToGateway() {
        assertTrue("CALL" in Commands.REMOTE_HANDLES)
        assertFalse("CALL" in Commands.GATEWAY_HANDLES)
        assertTrue("ANSWER" in Commands.GATEWAY_HANDLES)
        assertFalse("ANSWER" in Commands.REMOTE_HANDLES)
    }
}
