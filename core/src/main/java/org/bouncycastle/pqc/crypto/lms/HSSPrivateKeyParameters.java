package org.bouncycastle.pqc.crypto.lms;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bouncycastle.pqc.crypto.ExhaustedPrivateKeyException;

public class HSSPrivateKeyParameters
    extends HSSKeyParameters
{
    private final LMSPrivateKeyParameters rootKey;
    private final int l;
    private final boolean limited;

    private List<LMSPrivateKeyParameters> keys;
    private List<LMSSignature> sig;

    public HSSPrivateKeyParameters(int l, LMSPrivateKeyParameters[] keys, LMSSignature[] sig)
    {
        super(true);

        this.l = l;
        this.keys = Collections.unmodifiableList(Arrays.asList(keys.clone()));
        this.sig = Collections.unmodifiableList(Arrays.asList(sig.clone()));
        this.rootKey = this.keys.get(0);
        this.limited = false;
    }

    private HSSPrivateKeyParameters(int l, List<LMSPrivateKeyParameters> keys, List<LMSSignature> sig)
    {
        this(l, keys, sig, false);
    }

    private HSSPrivateKeyParameters(int l, List<LMSPrivateKeyParameters> keys, List<LMSSignature> sig, boolean limited)
    {
        super(true);

        this.l = l;
        this.keys = Collections.unmodifiableList(new ArrayList<LMSPrivateKeyParameters>(keys));
        this.sig = Collections.unmodifiableList(new ArrayList<LMSSignature>(sig));
        this.rootKey = this.keys.get(0);
        this.limited = limited;
    }

    static HSSPrivateKeyParameters getInstance(Object src, int maxDepth, int maxSecretSize)
        throws Exception
    {
        if (src instanceof HSSPrivateKeyParameters)
        {
            return (HSSPrivateKeyParameters)src;
        }
        else if (src instanceof DataInputStream)
        {
            if (((DataInputStream)src).readInt() != 0)
            {
                throw new LMSException("unknown version for hss private key");
            }
            int d = ((DataInputStream)src).readInt();
            if (d > maxDepth)
            {
                throw new LMSException("depth encoded exceeds maxDepth of " + maxDepth);
            }

            ArrayList<LMSPrivateKeyParameters> keys = new ArrayList<LMSPrivateKeyParameters>();
            ArrayList<LMSSignature> signatures = new ArrayList<LMSSignature>();

            for (int t = 0; t < d; t++)
            {
                keys.add(LMSPrivateKeyParameters.getInstance(src, maxSecretSize));
            }

            for (int t = 0; t < d - 1; t++)
            {
                signatures.add(LMSSignature.getInstance(src));
            }

            return new HSSPrivateKeyParameters(d, keys, signatures);

        }
        else if (src instanceof byte[])
        {
            InputStream in = null;
            try // 1.5 / 1.6 compatibility
            {
                in = new DataInputStream(new ByteArrayInputStream((byte[])src));
                return getInstance(in, maxDepth,maxSecretSize);
            }
            finally
            {
                if (in != null) in.close();
            }

        }
        else if (src instanceof InputStream)
        {
            return getInstance(new DataInputStream((InputStream)src), maxDepth, maxSecretSize);
        }

        throw new IllegalArgumentException("cannot parse " + src);
    }

    public int getL()
    {
        return l;
    }

    public long getIndex()
    {
        long used = 0;

        int last = keys.size() - 1;

        for (int t = 0; t < keys.size(); t++)
        {
            LMSPrivateKeyParameters key = keys.get(t);
            int lim = (1 << key.getParameters().getH());

            if (t == last)
            {
                used += key.getIndex();
            }
            else
            {
                if (key.getIndex() - 1 > 0)
                {
                    used += (key.getIndex() - 1) * lim;
                }
            }
        }

        return used;
    }

    public long getUsagesRemaining()
    {
        long used = 0;
        long possible = 1;

        int last = keys.size() - 1;

        for (int t = 0; t < keys.size(); t++)
        {
            LMSPrivateKeyParameters key = keys.get(t);
            int lim = (1 << key.getParameters().getH());
            possible *= lim;

            if (t == last)
            {
                used += key.getIndex();
            }
            else
            {
                if (key.getIndex() - 1 > 0)
                {
                    used += (key.getIndex() - 1) * lim;
                }
            }
        }

        return possible - used;
    }

    synchronized LMSPrivateKeyParameters getNextSigningKey(SecureRandom entropySource)
    {
        //
        // Algorithm 8
        //
        // Step 1.

        int L = this.getL();

        int d = L;
        List<LMSPrivateKeyParameters> prv = this.getKeys();
        while (prv.get(d - 1).getUsagesRemaining() == 0)
        {
            if (limited || d == 1) // we've exhausted the zero layer.
            {
                throw new ExhaustedPrivateKeyException("hss private key is exhausted");
            }
            d = d - 1;
        }

        while (d < L)
        {
            this.addNewKey(d, entropySource);
            d = d + 1;
        }

        return this.getKeys().get(L - 1);
    }

    public HSSPrivateKeyParameters getNextKey()
    {
        synchronized (this)
        {
            HSSPrivateKeyParameters keyParameters = this.extractKeyShard(1);

            return keyParameters;
        }
    }

    /**
     * Return a key that can be used usageCount times.
     * <p>
     * Note: this will use the range [index...index + usageCount) for the current key.
     * </p>
     *
     * @param usageCount the number of usages the key should have.
     * @return a key based on the current key that can be used usageCount times.
     */
    public HSSPrivateKeyParameters extractKeyShard(int usageCount)
    {
        synchronized (this)
        {
            LMSPrivateKeyParameters sKey = this.getKeys().get(getL() - 1);
            if (sKey.getUsagesRemaining() < usageCount)
            {
                throw new IllegalArgumentException("usageCount exceeds usages remaining in current leaf");
            }
            List<LMSPrivateKeyParameters> keys = new ArrayList<LMSPrivateKeyParameters>(this.getKeys());

            keys.set(getL() - 1, sKey.extractKeyShard(usageCount));

            return new HSSPrivateKeyParameters(l, keys, sig, true);
        }
    }

    public synchronized List<LMSPrivateKeyParameters> getKeys()
    {
        return keys;
    }

    public synchronized List<LMSSignature> getSig()
    {
        return sig;
    }

    public synchronized HSSPublicKeyParameters getPublicKey()
    {
        return new HSSPublicKeyParameters(l, rootKey.getPublicKey());
    }

    private void addNewKey(int d, SecureRandom source)
    {
        List<LMSPrivateKeyParameters> newKeys = new ArrayList<LMSPrivateKeyParameters>(keys);

        byte[] I = new byte[16];
        source.nextBytes(I);

        byte[] rootSecret = new byte[32];
        source.nextBytes(rootSecret);

        newKeys.set(d, LMS.generateKeys(rootKey.getParameters(), rootKey.getOtsParameters(), 0, I, rootSecret));

        List<LMSSignature> newSig = new ArrayList<LMSSignature>(sig);

        newSig.set(d - 1, LMS.generateSign(newKeys.get(d - 1), newKeys.get(d).getPublicKey().getEncoded()));

        // TODO restore
        //newSig.set(d - 1, LMS.generateSign(newList.get(d), newList.get(d - 1).getPublicKey().getEncoded(), source));

        this.keys = Collections.unmodifiableList(newKeys);
        this.sig = Collections.unmodifiableList(newSig);

    }

    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (o == null || getClass() != o.getClass())
        {
            return false;
        }

        HSSPrivateKeyParameters that = (HSSPrivateKeyParameters)o;

        if (l != that.l)
        {
            return false;
        }
        if (keys != null ? !keys.equals(that.keys) : that.keys != null)
        {
            return false;
        }
        if (sig != null ? !sig.equals(that.sig) : that.sig != null)
        {
            return false;
        }
        return rootKey != null ? rootKey.equals(that.rootKey) : that.rootKey == null;
    }

    @Override
    public int hashCode()
    {
        int result = l;
        result = 31 * result + (keys != null ? keys.hashCode() : 0);
        result = 31 * result + (sig != null ? sig.hashCode() : 0);
        result = 31 * result + (rootKey != null ? rootKey.hashCode() : 0);
        return result;
    }

    @Override
    public synchronized byte[] getEncoded()
        throws IOException
    {
        //
        // Private keys are implementation dependent.
        //

        Composer composer = Composer.compose()
            .u32str(0) // Version.
            .u32str(l); // Depth

        for (LMSPrivateKeyParameters key : keys)
        {
            composer.bytes(key);
        }

        for (LMSSignature s : sig)
        {
            composer.bytes(s);
        }

        return composer.build();
    }
}