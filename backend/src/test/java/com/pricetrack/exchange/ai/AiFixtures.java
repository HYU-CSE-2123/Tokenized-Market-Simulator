package com.pricetrack.exchange.ai;
import java.nio.file.Path;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
final class AiFixtures {
    static final AuthenticatedUser ADMIN=new AuthenticatedUser(1L,"admin",UserRole.ADMIN);
    static AiProperties properties(Path root,Path manifest,String jdbc) {
        return new AiProperties(true,jdbc,"exchange_ai","ai-local-test-only",root.toString(),manifest.toString(),
                "test-not-a-real-key","http://127.0.0.1:1/","text-embedding-3-small","gpt-5.6-terra",800,60,5,.3,1);
    }
    static float[] vector(int index) {float[] result=new float[1536];result[index]=1;return result;}
}

