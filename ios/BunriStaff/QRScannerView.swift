import AVFoundation
import SwiftUI

/// カメラの映像を出して QR を読む。`isActive` が false の間は止める(電池と、読み取りの二重処理を避ける)。
struct QRScannerView: UIViewRepresentable {
    let isActive: Bool
    let onCode: (String) -> Void

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = context.coordinator.session
        view.previewLayer.videoGravity = .resizeAspectFill
        context.coordinator.configure()
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        context.coordinator.onCode = onCode
        context.coordinator.setRunning(isActive)
    }

    static func dismantleUIView(_ view: PreviewView, coordinator: Coordinator) {
        coordinator.setRunning(false)
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let session = AVCaptureSession()
        var onCode: (String) -> Void = { _ in }
        private let queue = DispatchQueue(label: "bunrisai.staff.camera")
        private var configured = false

        func configure() {
            guard !configured,
                  let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else { return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            configured = true
        }

        func setRunning(_ run: Bool) {
            queue.async { [session] in
                if run, !session.isRunning { session.startRunning() }
                if !run, session.isRunning { session.stopRunning() }
            }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            for object in metadataObjects {
                if let code = object as? AVMetadataMachineReadableCodeObject, let text = code.stringValue {
                    onCode(text)
                    return
                }
            }
        }
    }
}
